package io.grpc.internal;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.Before;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

/**
 * Tests for {@link SingleTransportChannel}.
 */
@RunWith(JUnit4.class)
public class SingleTransportChannelTest {
	private ClientTransport testTransport;
	private Executor testExecutor;
	private String testAuthority;
	private ScheduledExecutorService testDeadlineCancellationExecutor;
	
	
	@Before
	public void SetUp(){
		testTransport = new EmptyClientTransport();
		testExecutor = new Executor(){
			@Override
			public void execute(Runnable command) {
				// TODO Auto-generated method stub
				
			}		
		};
		testAuthority = "testauthority";
		testDeadlineCancellationExecutor = new EmptyScheduledExecutorService();
	}
	
	public void testAuthority(){
		SingleTransportChannel testChannel = new SingleTransportChannel(testTransport, testExecutor, 
				testDeadlineCancellationExecutor, testAuthority);
		assertEquals(testChannel.authority(), testAuthority);
	}

	
	private void assertEquals(String authority, String testAuthority2) {
		// TODO Auto-generated method stub
		
	}


	private class EmptyClientTransport implements ClientTransport{

		@Override
		public ClientStream newStream(MethodDescriptor<?, ?> method, Metadata headers) {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public void ping(PingCallback callback, Executor executor) {
			// TODO Auto-generated method stub
			
		}
		
		
	}
	
	private class EmptyScheduledExecutorService implements ScheduledExecutorService{

		@Override
		public void shutdown() {
			// TODO Auto-generated method stub
			
		}

		@Override
		public List<Runnable> shutdownNow() {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public boolean isShutdown() {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public boolean isTerminated() {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public <T> Future<T> submit(Callable<T> task) {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public <T> Future<T> submit(Runnable task, T result) {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public Future<?> submit(Runnable task) {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
				throws InterruptedException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
				throws InterruptedException, ExecutionException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
				throws InterruptedException, ExecutionException, TimeoutException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public void execute(Runnable command) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
				TimeUnit unit) {
			// TODO Auto-generated method stub
			return null;
		}
		
	}

}
