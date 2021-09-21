# Android gRPC/BinderChannel Status Codes

## Background

[BinderChannel](https://github.com/grpc/proposal/blob/master/L73-java-binderchannel.md) is a gRPC transport that lets Android apps communicate across processes using familiar gRPC concepts and APIs. A BinderChannel-backed gRPC request can fail for many Android-specific reasons, both at `ServiceConnection` establishment and at `transact()` time. These transport-specific failures must be reported to clients using [gRPC’s standard canonical status code abstraction](https://github.com/grpc/grpc/blob/master/doc/statuscodes.md#status-codes-and-their-use-in-grpc). This document enumerates the BinderChannel errors one can expect to encounter, specifies a canonical status code mapping for each possibility and discusses how clients should handle them.


## Status Code Mapping

Consider the table that follows as an BinderChannel-specific addendum to the “[Codes that may be returned by the gRPC libraries](https://github.com/grpc/grpc/blob/master/doc/statuscodes.md#status-codes-and-their-use-in-grpc)” document. Mappings in that table that share a status code with one of the binder-specific mappings are repeated here for comparison.

<table>
  <tr>
   <td><strong>#</strong>
   </td>
   <td><strong>Error Case</strong>
   </td>
   <td><strong>Android API Manifestation</strong>
   </td>
   <td><strong>Status Code</strong>
   </td>
   <td><strong>Expected Client Handling</strong>
   </td>
  </tr>
  <tr>
   <td>1
   </td>
   <td>Server app not installed
   </td>
   <td rowspan="5" >bindService() returns false
   </td>
   <td rowspan="8" ><p>UNIMPLEMENTED<p>“The operation is not implemented or is not supported / enabled in this service.”
   </td>
   <td rowspan="9" >Direct the user to install/reinstall the server app.
   </td>
  </tr>
  <tr>
   <td>2
   </td>
   <td>Old version of the server app doesn’t declare the target android.app.Service in its manifest.
   </td>
  </tr>
  <tr>
   <td>3
   </td>
   <td>Target android.app.Service is disabled
   </td>
  </tr>
  <tr>
   <td>4
   </td>
   <td>The whole server app is disabled
   </td>
  </tr>
  <tr>
   <td>5
   </td>
   <td>Server app predates <a href="https://developer.android.com/guide/topics/permissions/overview">the Android M permissions model</a> and the user must review and approve some newly requested permissions before it can run.
   </td>
  </tr>
  <tr>
   <td>6
   </td>
   <td>Target android.app.Service doesn’t recognize grpc binding Intent (old version of server app?)
   </td>
   <td>onNullBinding() ServiceConnection callback
   </td>
  </tr>
  <tr>
   <td>7
   </td>
   <td>Method not found on the io.grpc.Server (old version of server app?)
   </td>
   <td rowspan="2" >N/A
   </td>
  </tr>
  <tr>
   <td>8
   </td>
   <td>Request cardinality violation (old version of server app expects unary rather than streaming, say)
   </td>
  </tr>
  <tr>
   <td>9
   </td>
   <td>Old version of the server app exposes target android.app.Service but doesn’t android:export it.
   </td>
   <td rowspan="3" >bindService() throws SecurityException
   </td>
   <td rowspan="5" ><p>PERMISSION_DENIED<p>
“The caller does not have permission to execute the specified operation …”
   </td>
  </tr>
  <tr>
   <td>10
   </td>
   <td>Target android.app.Service requires an &lt;android:permission&gt; that client doesn’t hold. 
   </td>
   <td>Prompt the user to grant the needed Android permission
   </td>
  </tr>
  <tr>
   <td>11
   </td>
   <td>Violations of the security policy for miscellaneous Android features like android:isolatedProcess, android:externalService, android:singleUser, instant apps, BIND_TREAT_LIKE_ACTIVITY, etc, 
   </td>
   <td rowspan="3" >Give up - This is a programming or packaging error that only the app developer can fix.
   </td>
  </tr>
  <tr>
   <td>12
   </td>
   <td>Calling Android UID not allowed by ServerSecurityPolicy 
   </td>
   <td rowspan="2" >N/A
   </td>
  </tr>
  <tr>
   <td>13
   </td>
   <td>Server Android UID not allowed by client’s SecurityPolicy
   </td>
  </tr>
  <tr>
   <td rowspan="3" >14
   </td>
   <td rowspan="3" >Server process crashed or killed with request in flight.
   </td>
   <td>onDisconnected() ServiceConnection callback
   </td>
   <td rowspan="6" ><p>UNAVAILABLE
<p>
“The service is currently unavailable. This is most likely a transient condition, which can be corrected by retrying with a backoff ...”
   </td>
   <td rowspan="6" >Retry with exponential backoff and deadline (see <a href="https://grpc.github.io/grpc-java/javadoc/io/grpc/ManagedChannelBuilder.html#enableRetry--">ManagedChannelBuilder#enableRetry()</a>
   </td>
  </tr>
  <tr>
   <td>onBinderDied() IBinder.DeathRecipient callback
   </td>
  </tr>
  <tr>
   <td>IBinder.transact() throws DeadObjectException
   </td>
  </tr>
  <tr>
   <td>15
   </td>
   <td>Server app is currently being upgraded to a new version
   </td>
   <td rowspan="2" >onBindingDied() ServiceConnection callback
   </td>
  </tr>
  <tr>
   <td>16
   </td>
   <td>The whole server app or the target android.app.Service was disabled
   </td>
  </tr>
  <tr>
   <td>17
   </td>
   <td>Binder transaction buffer overflow
   </td>
   <td>IBinder.transact() throws TransactionTooLargeException
   </td>
  </tr>
  <tr>
   <td>18
   </td>
   <td>Source Context for bindService() is destroyed with a request in flight
   </td>
   <td>onDestroy()
   </td>
   <td rowspan="2" ><p>CANCELLED
<p>
“The operation was cancelled, typically by the caller.”
   </td>
   <td rowspan="2" >Give up for now.
<p>
(Re. 18: The caller can try  again later when the user opens the source Activity or restarts the source Service) 
   </td>
  </tr>
  <tr>
   <td>19
   </td>
   <td>Client application cancelled the request
   </td>
   <td>N/A
   </td>
  </tr>
  <tr>
   <td rowspan="2" >19
   </td>
   <td rowspan="2" >Bug in Android itself or the way the io.grpc.binder transport uses it.
   </td>
   <td>IBinder.transact() returns false
   </td>
   <td rowspan="4" ><p>INTERNAL
<p>
“This means that some invariants expected by the underlying system have been broken. … Reserved for serious errors.”
   </td>
   <td rowspan="4" >Give up - This is a programming error that only the app or grpc developers can fix
   </td>
  </tr>
  <tr>
   <td>bindService() throws IllegalArgumentException
   </td>
  </tr>
  <tr>
   <td>20
   </td>
   <td>Flow-control protocol violation
   </td>
   <td rowspan="2" >N/A
   </td>
  </tr>
  <tr>
   <td>21
   </td>
   <td>Can’t parse request/response proto
   </td>
  </tr>
</table>


### Ambiguity

We say a status code is ambiguous if it maps to two error cases that reasonable clients want to handle differently. For instance, a client may have good reasons to handle error cases 9 and 10 above differently. But they can’t do so based on status code alone because those error cases map to the same one. 

In contrast, for example, even though error case 18 and 19 both map to the status code (`CANCELLED`), they are not ambiguous because we see no reason that clients would want to distinguish them. In both cases, clients will simply give up on the request.


#### Ambiguity of PERMISSION_DENIED and Mitigations

The mapping above has only one apparently ambiguous status code: `PERMISSION_DENIED`. However, this isn’t so bad because of the following:

The use of `<android:permission>`s for inter-app IPC access control (error case 10) is uncommon. Instead, we recommend that server apps only allow IPC from a limited set of client apps known in advance and identified by signature.

However, there may be gRPC server apps that want to use custom &lt;android:permission&gt;’s to let the end user decide which arbitrary other apps can make use of its gRPC services. In that case, clients should preempt error case 10 simply by [checking whether they hold the required permissions](https://developer.android.com/training/permissions/requesting) before sending a request.

Server apps can avoid error case 9 by never reusing an android.app.Service as a gRPC host if it has ever been android:exported=false in some previous app version. Instead they should simply create a new android.app.Service for this purpose.

Only error cases 11 - 13 remain, making `PERMISSION_DENIED` unambiguous for the purpose of error handling. Reasonable client apps can handle it in a generic way by displaying an error message and/or proceeding with degraded functionality.


#### Non-Ambiguity of UNIMPLEMENTED

The `UNIMPLEMENTED` status code corresponds to quite a few different problems with the server app: It’s either not installed, too old, or disabled in whole or in part. Despite the diversity of underlying error cases, we believe most client apps will and should handle `UNIMPLEMENTED` in the same way: by sending the user to the app store to (re)install the server app. Reinstalling might be overkill for the disabled cases but most end users don't know what it means to enable/disable an app and there’s neither enough space in a UI dialog nor enough reader attention to explain it. Reinstalling is something users likely already understand and very likely to cure problems 1-8.


## Detailed Discussion of Binder Failure Modes

### IBinder.transact() returns false

According to the [docs](https://developer.android.com/reference/android/os/IBinder#transact(int,%20android.os.Parcel,%20android.os.Parcel,%20int)), false “generally means the transaction code was not understood.” This is true for synchronous transactions but all gRPC/BinderChannel transactions are `FLAG_ONEWAY` meaning the calling thread doesn’t wait around for the server to return from `onTransact()`. Examination of the AOSP source code shows several additional undocumented reasons `transact()` could return false but all of these cases should be impossible and aren’t things that reasonable apps want to handle.

### IBinder.transact() Throws an Exception

According to the docs, `transact()` can throw `RemoteException` but the significance of this exception [isn’t documented](https://developer.android.com/reference/android/os/IBinder#transact(int,%20android.os.Parcel,%20android.os.Parcel,%20int)). By inspection of the AOSP source, we see there are several cases:

1. The remote process is no longer alive (`android.os.DeadObjectException`)
2. The IPC buffer is full (`android.os.TransactionTooLargeException`)
3. Certain internal errors from the underlying `ioctl()` system call.

Status code mappings: 
<table>
  <tr>
   <td><strong>Exception</strong>
   </td>
   <td><strong>Status Code</strong>
   </td>
   <td><strong>Rationale</strong>
   </td>
  </tr>
  <tr>
   <td>android.os.DeadObjectException
   </td>
   <td>UNAVAILABLE
   </td>
   <td>So the caller can retry against a new incarnation of the server process
   </td>
  </tr>
  <tr>
   <td>android.os.TransactionTooLargeException
   </td>
   <td>UNAVAILABLE
   </td>
   <td>These are usually transient. A retry is likely to succeed later when demand for the IPC buffer subsides.
   </td>
  </tr>
  <tr>
   <td>Some other RemoteException
   </td>
   <td>INTERNAL
   </td>
   <td rowspan="2" >So the caller doesn’t bother retrying
   </td>
  </tr>
  <tr>
   <td>Some other RuntimeException
   </td>
   <td>INTERNAL
   </td>
  </tr>
</table>


### bindService() returns false

According to the [docs](https://developer.android.com/reference/android/content/Context#bindService(android.content.Intent,%20android.content.ServiceConnection,%20int)), this bindService() returns false when “the system couldn't find the service or if your client doesn't have permission to bind to it.” However, the part about permission is somewhat misleading.

According to a review of the AOSP source code, there are in fact several cases:

1. The target package is not installed
2. The target package is installed but does not declare the target Service in its manifest.
3. The target package requests dangerous permissions but targets sdk &lt;= M and therefore requires a permissions review, but the caller is not running in the foreground and so it would be inappropriate to launch the review UI.

Status code mapping: **UNIMPLEMENTED**

(1) and (2) are interesting new possibilities unique to on-device RPC. (1) is straightforward and the most likely cause of (2) is that the user has an old version of the server app installed that predates its gRPC integration. Many clients will want to handle these cases, likely by directing the user to the app store in order to install/upgrade the server.

Unfortunately `UNIMPLEMENTED` doesn’t capture (3) but none of the other canonical status codes do either and we expect this case to be extremely rare.


### bindService() throws SecurityException

According to the [docs](https://developer.android.com/reference/android/content/Context#bindService(android.content.Intent,%20android.content.ServiceConnection,%20int)), SecurityException is thrown “if the calling app does not have permission to bind to the given service”. There are quite a few specific cases:

1. The target Service sets `android:exported = “false”` in its manifest but the caller is in a different app.
2. The target Service declares a required permission in its manifest but the calling package doesn’t have it. 
3. The target Service is marked as `android:singleton` in the manifest but doesn’t hold the `INTERACT_ACROSS_USERS` permission. 
4. The caller is an `android:isolatedProcess`.
5. The caller requested certain security-related flags on the binding without the necessary permission (gRPC/BinderChannel doesn’t do this)
6. … according to the source code, a long tail of unlikely security-related internal errors.

Status code mapping: **PERMISSION_DENIED**.

### bindService() throws IllegalArgumentException

There are a couple cases:

1. The binding Intent is not explicit.
2. The binding Intent contains file descriptors.

Status Code mapping: **INTERNAL**. These cases should be impossible. 


### onBindingDied() ServiceConnection callback

According to the [docs](https://developer.android.com/reference/android/content/ServiceConnection#onBindingDied(android.content.ComponentName)): “... This means the interface will never receive another connection. The application will need to unbind and rebind the connection to activate it again. This may happen, for example, if the application hosting the service it is bound to has been updated.”

Status code mapping: **UNAVAILABLE**

`UNAVAILABLE` is the best mapping since a retry is likely to succeed in the near future once the server application finishes updating. 


### onNullBinding() ServiceConnection callback

According to the [docs](https://developer.android.com/reference/android/content/ServiceConnection#onNullBinding(android.content.ComponentName)): “Called when the service being bound has returned null from its `onBind()` method. This indicates that the attempting service binding represented by this `ServiceConnection` will never become usable.”

Status code mapping: **UNIMPLEMENTED**

`UNIMPLEMENTED` is used here because a retry is likely to fail for the same reason. The most likely root cause for a null binding is an older version of the server app where the `android.app.Service` exists but either doesn’t implement `onBind()` or doesn’t recognize the `grpc.io.action.BIND` Intent action.


### onServiceDisconnected() ServiceConnection callback

According to the [docs](https://developer.android.com/reference/android/content/ServiceConnection#onServiceDisconnected(android.content.ComponentName)): “Called when a connection to the Service has been lost. This typically happens when the process hosting the service has crashed or been killed ...”

Status code mapping: **UNAVAILABLE**

`UNAVAILABLE` is used here since a retry is likely to succeed against a newly restarted instance of the server.


### Response Parcel Contains an Exception

Android’s Parcel class exposes a mechanism for marshalling certain types of `RuntimeException`s between traditional Binder IPC peers. However we won’t consider this case because gRPC/BinderChannel doesn’t use this mechanism. In fact, all BinderChannel transactions are `FLAG_ONE_WAY` so there is no response Parcel.


### Source Context for bindService() is Destroyed

The calling Activity or Service Context might be destroyed with a gRPC request in flight. Apps should cease operations when the Context hosting it goes away and this includes cancelling any outstanding RPCs.  

Status code mapping: **CANCELLED**