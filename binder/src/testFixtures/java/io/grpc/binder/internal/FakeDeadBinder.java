/*
 * Copyright 2025 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.binder.internal;

import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;
import java.io.FileDescriptor;

/** An {@link IBinder} that behaves as if its hosting process has died, for testing. */
public class FakeDeadBinder implements IBinder {
  @Override
  public boolean isBinderAlive() {
    return false;
  }

  @Override
  public IInterface queryLocalInterface(String descriptor) {
    return null;
  }

  @Override
  public String getInterfaceDescriptor() throws RemoteException {
    throw new DeadObjectException();
  }

  @Override
  public boolean pingBinder() {
    return false;
  }

  @Override
  public void dump(FileDescriptor fd, String[] args) throws RemoteException {
    throw new DeadObjectException();
  }

  @Override
  public void dumpAsync(FileDescriptor fd, String[] args) throws RemoteException {
    throw new DeadObjectException();
  }

  @Override
  public boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
    throw new DeadObjectException();
  }

  @Override
  public void linkToDeath(DeathRecipient r, int flags) throws RemoteException {
    throw new DeadObjectException();
  }

  @Override
  public boolean unlinkToDeath(DeathRecipient deathRecipient, int flags) {
    // No need to check whether 'deathRecipient' was ever actually passed to linkToDeath(): Per our
    // API contract, if "the IBinder has already died" we never throw and always return false.
    return false;
  }
}
