/**
 * Copyright Sergey Olefir
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
package io.github.solf.extra2.testutil;

import static io.github.solf.extra2.util.NullUtil.nn;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;
import javax.annotation.NonNullByDefault;

import com.conversantmedia.util.concurrent.DisruptorBlockingQueue;
import com.conversantmedia.util.concurrent.SpinPolicy;

import io.github.solf.extra2.exception.AssertionException;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;

/**
 * Implementation of {@link OutputStream} that can always be interrupted and
 * provides mechanisms (via {@link #kill()}, {@link #queueWriteException(Throwable)})
 * to inject exceptions into writing threads (e.g. to test how those threads
 * handle write exceptions). 
 *
 * @author Sergey Olefir
 */
@NonNullByDefault
public class RevivableOutputStream extends OutputStream
{
	/**
	 * Indicates that transfer int is empty.
	 */
	private static final int VALUE_HIGHEST_SPECIAL = -1_000_000_000;
	
	/**
	 * Indicates that transfer int is requesting flush.
	 */
	private static final int VALUE_FLUSH = -1_000_000_001;
	
	/**
	 * Indicates that transfer int is requesting flush.
	 */
	private static final int VALUE_TRANSFER_THREAD_EXIT = -1_000_000_002;
	
	/**
	 * Instance used as a token in {@link #availableSpaceNotificationQueue}
	 */
	private static final Object SPACE_TOKEN = new Object();
	
	/**
	 * Counter for thread instances.
	 */
	private static final AtomicInteger workerThreadInstanceCounter = new AtomicInteger(0);
	
	/**
	 * Output stream this stream writes to.
	 */
	private final OutputStream out;
	
	/**
	 * Internal buffer size.
	 */
	@SuppressWarnings("unused")
	private final int bufferSize;
	
	/**
	 * Transfer integer used to pass data from client thread to worker thread.  
	 */
	private final DisruptorBlockingQueue<Integer> transferQueue;
	
	/**
	 * This queue is used to indicate that there *might* be an empty space in {@link #transferQueue}
	 */
	private final DisruptorBlockingQueue<Object> availableSpaceNotificationQueue = new DisruptorBlockingQueue<>(1, SpinPolicy.BLOCKING);
	
	/**
	 * Whether stream has been closed
	 */
	private volatile boolean closed = false;
	
	/**
	 * Transfer thread exception.
	 */
	@Nullable
	private volatile Throwable transferThreadException = null;
	
	/**
	 * 'killed' flag.
	 */
	private volatile boolean killed = false;
	
	/**
	 * Exception that should be thrown for 'killed' threads.
	 */
	@Nullable
    private volatile Throwable killException = null;
	
    
    /**
     * Queue of exceptions to be thrown during write(s)
     */
    private final ConcurrentLinkedDeque<Throwable> exceptionQueue = new ConcurrentLinkedDeque<>();
    
    /**
     * flush() responses queue.
     */
    private final DisruptorBlockingQueue<Object> flushResponsesQueue = new DisruptorBlockingQueue<>(1, SpinPolicy.BLOCKING);
	
    /**
     * Worker thread -- for tests if nothing else.
     */
    private final Thread workerThread;
    
    /**
     * Whether thrown exceptions (particularly those that are requested via
     * {@link #kill(Throwable)} and {@link #queueWriteException(Throwable)}) 
     * should be decorated with additional exception on the top (of the same
     * class and with the same message) that reflects the place where this
     * exception was triggered (as opposed to where it was originally created
     * which is often another thread and another place entirely).
     * <p>
     * Default is true
     */
    @Getter
    @Setter
    private volatile boolean decorateExceptions = true;
    
	/**
	 * Constructor.
	 * 
	 * @param bufferSize internal buffer size -- this many bytes can be written
	 * 		without blocking
	 */
	public RevivableOutputStream(OutputStream out, int bufferSize)
	{
		this.out = out;
		this.bufferSize = bufferSize;
		this.transferQueue = new DisruptorBlockingQueue<>(bufferSize, SpinPolicy.BLOCKING);
		
		// This is asynchronous worker thread that trasfers bytes from client threads
		// to destination output stream
		workerThread = new Thread(
		() -> {
			boolean cleanExit = false;
			Throwable exception = null;
			try
			{
				byte[] buffer = new byte[bufferSize];
				while(true)
				{
					// Might be space available...
					availableSpaceNotificationQueue.offer(SPACE_TOKEN);
					
					int size = 0;
					while(true)
					{
						Integer oNext;
						if (size == 0)
							oNext = transferQueue.take();
						else
							oNext = transferQueue.poll();
						
						if (oNext == null)
						{
							// Nothing else available, flush and go for the next loop
							if (size > 0)
								out.write(buffer, 0, size);
							break;
						}
						
						int next = oNext;
						if (next > VALUE_HIGHEST_SPECIAL)
						{
							// non-special value, just record it
							buffer[size++] = (byte)next;
							if (size == bufferSize)
							{
								out.write(buffer); // buffer is full, flush and go got the next loop
								break;
							}
						}
						else
						{
							// Special values.
							if (size > 0)
								out.write(buffer, 0, size); // flush any buffer so far
							
							switch (next)
							{
								case VALUE_FLUSH:
									flushResponsesQueue.offer(new Object());
									break;
								case VALUE_TRANSFER_THREAD_EXIT:
									cleanExit = true;
									return; // Cleanly exit transfer thread
								default:
									throw new AssertionException();
							}
							
							break; // go for the next loop
						}
					}
				}
			} catch (Throwable e)
			{
				cleanExit = false;
				exception = e;
			} finally
			{
				try
				{
					if (!cleanExit)
					{
						if (exception == null)
							exception = new AssertionException("No 'success' and no exception in RevivableOutputStream worker thread.");
						transferThreadException = exception;
					}
				} finally
				{
					try
					{
						flushResponsesQueue.offer(nn(exception));
					} finally
					{
						// Indicate to any waiting threads that they should proceed
						availableSpaceNotificationQueue.offer(SPACE_TOKEN);
					}
				}
			}
		},
			"RevivableOutputStream-Worker-" + Thread.currentThread().getName() + "-" + workerThreadInstanceCounter.incrementAndGet());
		workerThread.setDaemon(true);
		workerThread.start();
	}
	
	/* (non-Javadoc)
	 * @see java.io.OutputStream#write(int)
	 */
	@SneakyThrows
	@Override
	public void write(int b)
		throws IOException
	{
		try
		{
			while(true)
			{
				throwExceptionIfWriteShouldFail();
	            
	            // Try to add our byte...
	            if (transferQueue.offer(b))
	            	return;
	            
	            // No luck, wait for possible next space and go for another loop...
	            try
	            {
		            availableSpaceNotificationQueue.take();
	            } catch (InterruptedException e)
	            {
	            	throw new InterruptedIOException(e.toString());
	            }
			}
		} finally
		{
			// After this call exits, there *might* be a space in transfer queue
			availableSpaceNotificationQueue.offer(SPACE_TOKEN);
		}
	}

	/**
	 * @throws IOException
	 * @throws Throwable
	 */
	private void throwExceptionIfWriteShouldFail() throws Throwable
	{
		if (closed)
			throw new IOException("Stream Closed");
		{
			Throwable e = transferThreadException;
			if (e != null)
				throw decorateThrowableFallbackToOriginal(e);
		}
		if (killed)
		{
			Throwable e = killException;
			if (e != null)
				throw decorateThrowableFallbackToOriginal(e);
		    throw new IOException("Stream [temporarily] killed.");
		}
		{
			Throwable e = exceptionQueue.poll();
			if (e != null)
				throw decorateThrowableFallbackToOriginal(e);
		}
	}

	/* (non-Javadoc)
	 * @see java.io.OutputStream#flush()
	 */
	@SneakyThrows
	@Override
	public synchronized void flush()
		throws IOException
	{
		// Method is synchronized because we don't want concurrent accesses
		// to flush response queue
		
		if (closed)
			throw new IOException("Stream Closed");
		
		flushResponsesQueue.clear();
		write(VALUE_FLUSH);
		
		try
		{
			while(true)
			{
				throwExceptionIfWriteShouldFail();
				
				Object response = flushResponsesQueue.take();
				if (response instanceof Throwable)
					throw decorateThrowableFallbackToOriginal((Throwable)response);
				if (response == SPACE_TOKEN)
					continue; // spin to see if we should throw exception
				
				out.flush(); // Process flush on target stream
				break; // done
			}
		} catch (InterruptedException e)
		{
			throw new InterruptedIOException(e.toString());
		}
	}

	/* (non-Javadoc)
	 * @see java.io.OutputStream#close()
	 */
	@Override
	public synchronized void close()
		throws IOException
	{
		if (closed) // do nothing if already closed
			return;
		
		flush();
		write(VALUE_TRANSFER_THREAD_EXIT);
		
		closed = true;
		
		out.close();
	}
	

    /**
     * Kills this revivable output stream. Makes current and future write calls
     * immediately fail with IOException. The output stream may be revived through
     * {@link #resurrect()}. If this revivable output stream is already killed,
     * this method does nothing.
     * <p>
     * NOTE: this takes priority over {@link #queueWriteException(Throwable)}
     *
     * @see #resurrect()
     * @see #revive()
     */
    public void kill() {
    	kill(null);
    }
    
    /**
     * Kills this revivable output stream.
     * <p>
     * If given throwable is non-null, then current and future writes will throw 
     * that exception; if given throwable is null, then current and future writes
     * immediately throw IOException
     * <p> 
     * The output stream may be revived through {@link #resurrect()}. If this 
     * revivable output stream is already killed, then this method can be used
     * to modify throwable that is being thrown / EOF. 
     * <p>
     * NOTE: this takes priority over {@link #queueWriteException(Throwable)}
     *
     * @see #resurrect()
     * @see #revive()
     */
    public void kill(@Nullable Throwable e) {
    	killException = e;
    	killed = true;
    	availableSpaceNotificationQueue.offer(SPACE_TOKEN); // notify potentially waiting threads
    	flushResponsesQueue.offer(SPACE_TOKEN); // notify potentially waiting flush
    }
    
    /**
     * Queues exception to be thrown during the write -- either the currently 
     * processing write or the next one.
     * <p>
     * The exception is thrown only once, then the writing is processed normally.
     * <p>
     * If multiple exceptions are added, they are thrown 'in order'.
     * <p>
     * NOTE: kill(..) settings take priority over this
     */
    public void queueWriteException(Throwable e) throws IllegalStateException
    {
       	exceptionQueue.add(e);
    	availableSpaceNotificationQueue.offer(SPACE_TOKEN); // notify potentially waiting threads
    	flushResponsesQueue.offer(SPACE_TOKEN); // notify potentially waiting flush
    }

    /**
     * Same as {@link #resurrect()}
     */
    public void revive() {
        resurrect();
    }

    /**
     * Resurrects a killed revivable output stream. This makes it possible to
     * write to this output stream once again. If this revivable output stream is
     * not killed, this method does nothing.
     *
     * @see #kill()
     */
    public void resurrect() {
        killed = false;
    }
    
    /**
     * Decorates an exception if {@link #decorateExceptions} is true; does nothing
     * (returns original exception) if false
     * <p>
     * If cloning/decoration fails, returns original exception
     */
    private <T extends Throwable> T decorateThrowableFallbackToOriginal(T throwable)
    {
    	if (isDecorateExceptions())
    		return TestUtil.cloneThrowableAddCurrentStackNoFailFallbackToOriginal(throwable);
    		
    	return throwable;
    }
}
