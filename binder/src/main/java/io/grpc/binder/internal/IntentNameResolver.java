package io.grpc.binder.internal;

import static android.content.Intent.URI_INTENT_SCHEME;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static io.grpc.binder.internal.SystemApis.createContextAsUser;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.UserHandle;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusOr;
import io.grpc.SynchronizationContext;
import io.grpc.binder.AndroidComponentAddress;
import io.grpc.binder.ApiConstants;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;

/**
 * A {@link NameResolver} that resolves Android-standard "intent:" target URIs to the list of {@link
 * AndroidComponentAddress} that match it by manifest intent filter.
 */
final class IntentNameResolver extends NameResolver {
  private final URI targetUri;
  @Nullable private final UserHandle targetUser; // null means same user that hosts this process.
  private final Context targetUserContext;
  private final Executor offloadExecutor;
  private final Executor sequentialExecutor;
  private final SynchronizationContext syncContext;
  private final ServiceConfigParser serviceConfigParser;

  // Accessed only on `sequentialExecutor`
  @Nullable private PackageChangeReceiver receiver;

  // Accessed only on 'syncContext'.
  private boolean shutdown;
  private boolean lookupNeeded;
  @Nullable private Listener2 listener;

  @Nullable
  private ListenableFuture<ResolutionResult> lookupResultFuture; // != null when lookup in progress.

  // Servers discovered in PackageManager are especially untrusted. After all, an app can declare
  // any intent filter it wants. Use pre-auth to avoid giving unauthorized apps a chance to run.
  @EquivalentAddressGroup.Attr
  private static final Attributes CONSTANT_EAG_ATTRS =
      Attributes.newBuilder().set(ApiConstants.PRE_AUTH_SERVER_OVERRIDE, true).build();

  IntentNameResolver(Context context, URI targetUri, Args args) {
    this.targetUri = targetUri;
    this.targetUser = args.getArg(ApiConstants.TARGET_ANDROID_USER);
    this.targetUserContext =
        targetUser != null
            ? createContextAsUser(context, targetUser, /* flags= */ 0) // @SystemApi since R.
            : context;
    // This Executor is nominally optional but all grpc-java Channels provide it since 1.25.
    this.offloadExecutor =
        checkNotNull(args.getOffloadExecutor(), "NameResolver.Args.getOffloadExecutor()");
    // Ensures start()'s work runs before resolve()'s' work, and both run before shutdown()'s.
    this.sequentialExecutor = MoreExecutors.newSequentialExecutor(offloadExecutor);
    this.syncContext = args.getSynchronizationContext();
    this.serviceConfigParser = args.getServiceConfigParser();
  }

  @Override
  public void start(Listener2 listener) {
    checkState(this.listener == null, "Already started!");
    checkState(!shutdown, "Resolver is shutdown");
    this.listener = checkNotNull(listener);
    sequentialExecutor.execute(this::registerReceiver);
    resolve();
  }

  @Override
  public void refresh() {
    checkState(listener != null, "Not started!");
    resolve();
  }

  private void resolve() {
    syncContext.throwIfNotInThisSynchronizationContext();

    if (shutdown) {
      return;
    }

    // We can't block here in 'syncContext' so we offload PackageManager lookups to an Executor.
    // But offloading complicates things a bit because other calls can arrive while we wait for the
    // results. We keep 'listener' up-to-date with the latest state in PackageManager by doing:
    // 1. Only one lookup-and-report-to-listener operation at a time.
    // 2. At least one lookup-and-report-to-listener AFTER every PackageManager state change.
    if (lookupResultFuture == null) {
      lookupResultFuture = Futures.submit(this::lookupAndroidComponentAddress, sequentialExecutor);
      lookupResultFuture.addListener(this::onLookupComplete, syncContext);
    } else {
      // There's already a lookup in-flight but (2) says we need at least one more. Our sequential
      // Executor would be enough to ensure (1) but we also don't want a backlog of work to build up
      // if things change rapidly. Just make a note to start a new lookup when this one finishes.
      lookupNeeded = true;
    }
  }

  private void onLookupComplete() {
    syncContext.throwIfNotInThisSynchronizationContext();
    checkState(lookupResultFuture != null);
    checkState(lookupResultFuture.isDone());

    // Capture non-final `listener` here while we're on 'syncContext'.
    Listener2 listener = checkNotNull(this.listener);
    Futures.addCallback(
        lookupResultFuture, // Already isDone() so this execute()s immediately.
        new FutureCallback<ResolutionResult>() {
          @Override
          public void onSuccess(ResolutionResult result) {
            listener.onResult(result);
          }

          @Override
          public void onFailure(Throwable t) {
            listener.onError(Status.fromThrowable(t));
          }
        },
        sequentialExecutor);
    lookupResultFuture = null;

    if (lookupNeeded) {
      // One or more resolve() requests arrived while we were working on the last one. Just one
      // follow-on lookup can subsume all of them.
      lookupNeeded = false;
      resolve();
    }
  }

  @Override
  public String getServiceAuthority() {
    return "localhost";
  }

  @Override
  public void shutdown() {
    syncContext.throwIfNotInThisSynchronizationContext();
    if (!shutdown) {
      shutdown = true;
      sequentialExecutor.execute(this::maybeUnregisterReceiver);
    }
  }

  private ResolutionResult lookupAndroidComponentAddress() throws StatusException {
    Intent targetIntent = parseUri(targetUri);

    // Avoid a spurious UnsafeIntentLaunchViolation later. Since S, Android's StrictMode is very
    // conservative, marking any Intent parsed from a string as suspicious and complaining when you
    // bind to it. But all this is pointless with grpc-binder, which already goes even further by
    // not trusting addresses at all! Instead, we rely on SecurityPolicy, which won't allow a
    // connection to an unauthorized server UID no matter how you got there.
    targetIntent = sanitize(targetIntent);

    List<ResolveInfo> resolveInfoList = lookupServices(targetIntent);

    // Model each matching android.app.Service as an individual gRPC server with a single address.
    List<EquivalentAddressGroup> servers = new ArrayList<>();
    for (ResolveInfo resolveInfo : resolveInfoList) {
      targetIntent.setComponent(
          new ComponentName(resolveInfo.serviceInfo.packageName, resolveInfo.serviceInfo.name));
      servers.add(
          new EquivalentAddressGroup(
              AndroidComponentAddress.newBuilder()
                  .setBindIntent(targetIntent) // Makes a copy.
                  .setTargetUser(targetUser)
                  .build(),
              CONSTANT_EAG_ATTRS));
    }

    return ResolutionResult.newBuilder()
        .setAddressesOrError(StatusOr.fromValue(servers))
        // Empty service config means we get the default 'pick_first' load balancing policy.
        .setServiceConfig(serviceConfigParser.parseServiceConfig(ImmutableMap.of()))
        .build();
  }

  private List<ResolveInfo> lookupServices(Intent intent) throws StatusException {
    int flags = 0;
    if (Build.VERSION.SDK_INT >= 29) {
      // Don't match direct-boot-unaware Services that can't presently be created. We'll query again
      // after the user is unlocked. The MATCH_DIRECT_BOOT_AUTO behavior is actually the default but
      // being explicit here avoids an android.os.strictmode.ImplicitDirectBootViolation.
      flags |= PackageManager.MATCH_DIRECT_BOOT_AUTO;
    }
    List<ResolveInfo> intentServices =
        targetUserContext.getPackageManager().queryIntentServices(intent, flags);

    if (intentServices.isEmpty()) {
      throw Status.UNIMPLEMENTED
          .withDescription("Service not found for intent " + intent)
          .asException();
    }
    return intentServices;
  }

  private static Intent parseUri(URI targetUri) throws StatusException {
    try {
      return Intent.parseUri(targetUri.toString(), URI_INTENT_SCHEME);
    } catch (URISyntaxException uriSyntaxException) {
      throw Status.INVALID_ARGUMENT
          .withCause(uriSyntaxException)
          .withDescription("Failed to parse target URI " + targetUri + " as intent")
          .asException();
    }
  }

  // Returns a new Intent with the same action, data and categories as 'input'.
  private static Intent sanitize(Intent input) {
    Intent output = new Intent();
    output.setAction(input.getAction());
    output.setData(input.getData());

    Set<String> categories = input.getCategories();
    if (categories != null) {
      for (String category : categories) {
        output.addCategory(category);
      }
    }
    // Don't bother copying extras and flags since AndroidComponentAddress (rightly) ignores them.
    // Don't bother copying package or ComponentName either, since we're about to set that.
    return output;
  }

  final class PackageChangeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
      // Get off the main thread and into the correct SynchronizationContext.
      syncContext.executeLater(IntentNameResolver.this::resolve);
      offloadExecutor.execute(syncContext::drain);
    }
  }

  @SuppressLint("UnprotectedReceiver") // All of these are protected system broadcasts.
  private void registerReceiver() {
    checkState(receiver == null, "Already registered!");
    receiver = new PackageChangeReceiver();
    IntentFilter filter = new IntentFilter();
    filter.addDataScheme("package");
    filter.addAction(Intent.ACTION_PACKAGE_ADDED);
    filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
    filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
    filter.addAction(Intent.ACTION_PACKAGE_REPLACED);

    targetUserContext.registerReceiver(receiver, filter);

    if (Build.VERSION.SDK_INT >= 24) {
      // Clients running in direct boot mode must refresh() when the user is unlocked because
      // that's when `directBootAware=false` services become visible in queryIntentServices()
      // results. ACTION_BOOT_COMPLETED would work too but it's delivered with lower priority.
      targetUserContext.registerReceiver(receiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
    }
  }

  private void maybeUnregisterReceiver() {
    if (receiver != null) { // NameResolver API contract appears to allow shutdown without start().
      targetUserContext.unregisterReceiver(receiver);
      receiver = null;
    }
  }
}
