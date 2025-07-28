package io.grpc.binder.internal;

import static com.google.common.base.Preconditions.checkNotNull;

import android.content.Context;
import android.content.Intent;
import com.google.common.collect.ImmutableSet;
import io.grpc.NameResolver;
import io.grpc.NameResolver.Args;
import io.grpc.NameResolverProvider;
import io.grpc.NameResolverRegistry;
import io.grpc.binder.AndroidComponentAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * A {@link NameResolverProvider} that handles Android-standard "intent:" target URIs, resolving
 * them to the list of {@link AndroidComponentAddress} that match by manifest intent filter.
 */
public final class IntentNameResolverProvider extends NameResolverProvider {

  /** Defined by {@link Intent#URI_INTENT_SCHEME}. */
  public static final String ANDROID_INTENT_SCHEME = "intent";

  private static boolean isDefaultRegistered;

  private final Context context;

  /**
   * Creates a new IntentNameResolverProvider.
   *
   * @param context an Android Application {@link Context}, for access to PackageManager.
   */
  IntentNameResolverProvider(Context context) {
    this.context = checkNotNull(context);
  }

  /**
   * Registers a new instance of this {@link NameResolverProvider} in the {@link
   * NameResolverRegistry#getDefaultRegistry()}, unless a previous call has already done so.
   *
   * @param context any Android {@link Context}, not retained, for access to the Application
   */
  public static synchronized void maybeCreateAndDefaultRegister(Context context) {
    if (!isDefaultRegistered) {
      NameResolverRegistry.getDefaultRegistry()
          .register(new IntentNameResolverProvider(context.getApplicationContext()));
      isDefaultRegistered = true;
    }
  }

  @Override
  public String getDefaultScheme() {
    return ANDROID_INTENT_SCHEME;
  }

  @Nullable
  @Override
  public NameResolver newNameResolver(URI targetUri, final Args args) {
    if (Objects.equals(targetUri.getScheme(), ANDROID_INTENT_SCHEME)) {
      return new IntentNameResolver(context, targetUri, args);
    } else {
      return null;
    }
  }

  @Override
  public boolean isAvailable() {
    return true;
  }

  @Override
  public int priority() {
    return 5; // default.
  }

  @Override
  public ImmutableSet<Class<? extends SocketAddress>> getProducedSocketAddressTypes() {
    return ImmutableSet.of(AndroidComponentAddress.class);
  }
}
