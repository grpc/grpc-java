package io.grpc;

import io.grpc.Context.CheckReturnValue;
import io.grpc.PersistentHashArrayMappedTrie.Node;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Propagate a context which deliver scoped-values across API threads.
 * For example:
 * <ul>
 *   <li>Entitlements</li>
 *   <li>Application tracing data, both local and distributed</li>
 * </ul>
 * <p>By {@link #attach attaching} a Context instance to {@link Storage}, effectively a <b>scope</b> is formed for the context. The scope is bound to the current thread. Within a scope, the Context is accessible even across API boundaries via {@link #current}. By {@link #detach detaching} the Context, the scope is exited.
 * <p>Context instances are immutable and inherit state from their parent. To amend the current state, please create and attach a new Context instance thus replacing the previously bound one, e.g.:
 *
 * <pre>
 *   Context withEntitlement = Context.current().withValue(ENTITLEMENT_KEY, entitlement);
 *   withCredential.run(() -> { readUserRecords(userId, ENTITLEMENT_KEY.get()); });
 * </pre>
 *
 * <p>Scoped units of work are also represented by contexts.
 * The context must be cancelled with the unit of work is finished.
 * All descendant contexts will be targeted by the cancellation.
 * A context can have a {@link CancellationListener} attached to it.
 * This helps with notifying it when it or one of its ancestors has been cancelled.
 * It's ok to {@link #attach()} a cancelled context to make it current;
 * cancellation doesn't release the state stored by the context.
 * If you're interested in cancelling a context:
 * a) create a {@link CancellableContext}
 * b) when you're ready, call either one of these:
 * - {@link CancellableContext#cancel}
 * - {@link CancellableContext#detachAndCancel}
 *
 * <p>Contexts can be cancelled with a timeout based on the system nano clock.
 *
 * <p>Please be aware:
 * <ul>
 *   <li>You can incur memory leaks if you don't (a) detach() after you attach() in the same method, and cancel CancellableContext at some point in time</li>
 *   <li>Context instances don't force their stored state to be immutable even though they themselves are immutable</li>
 *   <li>Be careful to not depend too much on Context instances when developing an API. Also, remember that Context objects are not designed for transporting optional API parameters</li>
 *   <li>Please use {@link #ROOT} for a non-null object instead of creating a mocked version</li>
 * </ul> 
 */
/* @DoNotMock("Use ROOT for a non-null Context") // prevent dependencies being 'summoned' */
@CheckReturnValue
public class Context {

  static final Logger log = Logger.getLogger(Context.class.getName());

  // If you have a long context chain chances are you're doing it wrong
  // The threshold is just randomly chosen
  // VisibleForTesting
  static final int CONTET_DEPTH_WARN_THRESH = 1000;

}
