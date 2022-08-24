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
 * <p>Contexts are also used to represent a scoped unit of work. When the unit of work is done the context must be cancelled. The cancellation will cascade to all descendant contexts. You can add a {@link CancellationListener} to inform the context when it or one of its ancestors has been canceleld. Cancellation doesn't release the state stored by a Context and it's okay to {@link #attach()} an already cancelled Context to make it current. To cancel a Context (and its descendants), first create a {@link CancellableContext}. When it's time to signal a cancellation, call {@link CancellableContext#cancel} or {@link CancellableContext#detachAndCancel}.
 */
