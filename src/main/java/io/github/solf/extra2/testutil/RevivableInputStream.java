/*
 * Copyright (c) 2013 Jean Niklas L'orange. All rights reserved.
 *
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 * which can be found in the file LICENSE at the root of this distribution.
 *
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 *
 * You must not remove this notice, or any other, from this software.
 */

package io.github.solf.extra2.testutil;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.concurrent.ConcurrentLinkedDeque;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import io.github.solf.extra2.exception.AssertionException;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;

/**
 * A revivable input stream is an unbuffered input stream wrapping another input
 * stream. Its primary feature is that it allows to "kill" blocking
 * <code>.read</code> calls by calling <code>.kill</code>. Reading from the
 * stream can be resumed by calling <code>.resurrect</code>.
 * <p>
 * The common use for this is to avoid closing an input stream, while still be
 * able to cancel a blocking <code>.read</code> where you must use an input
 * stream. This is useful if you need to send a message to the thread which
 * attempts to read from the input stream.
 *
 * @author Jean Niklas L'orange
 * @since <code>com.hypirion.io 0.1.0</code>
 */

public class RevivableInputStream extends InputStream {
    private final InputStream in;

    private volatile boolean killed;
    private volatile boolean streamClosed;
    private volatile int requestedBytes;
    private volatile byte[] data;
    private volatile boolean requestData;
    private final Object dataLock;
    /**
     * Set to non-null if worker thread crashes.
     */
    @Nullable
    private volatile Throwable workerThreadException = null; // Solf: remove flag threadCrashed and replace with just this 
    
    /**
     * Queue of exceptions to be thrown during read(s)
     */
    private final ConcurrentLinkedDeque<Throwable> exceptionQueue = new ConcurrentLinkedDeque<>(); // Solf: added
    private volatile Throwable killException = null; // Solf: added

    private final ThreadReader reader;
    private final Thread readerThread;
    
    /**
     * Whether thrown exceptions (particularly those that are requested via
     * {@link #kill(Throwable)} and {@link #queueReadException(Throwable)}) 
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
     * Creates a new <code>RevivableInputStream</code> which wraps
     * <code>in</code>, giving it power to be killed and resurrected.
     */
    public RevivableInputStream(InputStream in) {
        this.in = in;
        killed = false;
        streamClosed = false;
        requestData = true;
        dataLock = new Object();
        workerThreadException = null;
        requestedBytes = -1;
        data = null;
        reader = new ThreadReader();
        readerThread = new Thread(reader);
        readerThread.setDaemon(true);
        readerThread.setName("RevivableReader " + in.hashCode());
        readerThread.start();
        while (requestData) {
            Thread.yield();
            // ensure no deadlock by waiting for the reader to get into its
            // correct state
        }
    }

    /**
     * Returns the number of bytes than can be read from this input stream
     * without blocking.
     *
     * Will as of now return 0.
     *
     * @return 0
     */
    @Override
	public synchronized int available() {
        return 0;
    }

    /**
     * Closes this revivable input stream and the underlying input stream, and
     * releases any with system resources (threads, memory) associated with this
     * stream.
     *
     * @exception IOException if the underlying <code>InputStream</code> throws
     * an <code>IOException</code>.
     */
    @Override
	public synchronized void close() throws IOException {
        synchronized (dataLock) {
            in.close();
            dataLock.notifyAll();
        }
    }

    /**
     * Reads the next byte of data from this revivable input stream. The value
     * byte is returned as an <code>int</code> in the range <code>0</code> to
     * <code>255</code>. This method blocks until no data is available, the end
     * of the stream is detected, an exception is thrown or if the reviable
     * input stream is (potentially temporarily) killed.
     *
     * @return the next byte of data, or <code>-1</code> if the end of the
     * stream is reached or the stream is killed.
     * @exception IOException if the underlying <code>InputStream</code> throws
     * an <code>IOException</code> when attempted to read. This exception will
     * be thrown every time read is called until the stream is closed.
     */
    @Override
	public synchronized int read() throws IOException {
        byte[] b = new byte[1];
        int count = 0;
        do {
            count = read(b, 0, 1);
        } while (count == 0);
        if (count == -1){
            return -1;
        }
        else {
        	// Solf: use proper conversion that won't return negative numbers which indicate EOF
//            return b[0];
            return Byte.toUnsignedInt(b[0]);
        }
    }

    @SneakyThrows // allows us to throw whatever exception was requested regardless of signature
    @Override
	public synchronized int read(byte[] b, int off, int len)
        throws IOException {
        synchronized (dataLock) {
            if (data == null) {
                requestedBytes = len;
                requestData = true;
                dataLock.notifyAll();
            }
            
            // Solf: rewrote this loop to do not repeat code.
            while(true)
            {
            	// First check if we need to abort early
                if (streamClosed)
                    return -1;
                {
                	Throwable e = workerThreadException;
	                if (e != null)
	                    throw decorateThrowableFallbackToOriginal(e);
                }
                if (killed)
                {
                	Throwable e = killException;
                	if (e != null)
                		throw decorateThrowableFallbackToOriginal(e);
                    return -1;
                }
                {
                	Throwable e = exceptionQueue.poll();
                	if (e != null)
                		throw decorateThrowableFallbackToOriginal(e);
                }
            	
                if (data != null) // If have some data, exit waiting
                	break;
                
                try
                {
                	dataLock.wait();
                }
                catch (InterruptedException ie) 
                {
                    throw new InterruptedIOException();
                }
            }
            
            // data must be non-null here due to loop above and synchronization
            int n = data.length;
            if (n < len) {
                int totRead = n;
                System.arraycopy(data, 0, b, off, n);
                data = null;

                // In case we can read additional data without blocking
                int additional = Math.min(in.available(), len - n);
                if (additional > 0) {
                    additional = in.read(b, off + n, additional);
                    // ^ sanity check
                }
                totRead += additional;
                return totRead;
            }
            else if (n > len) {
                System.arraycopy(data, 0, b, off, len);
                int diff = n - len;
                byte[] newData = new byte[diff];
                System.arraycopy(data, len, newData, 0, diff);
                data = newData;
                return len;
            }
            else { // here n == len
                System.arraycopy(data, 0, b, off, len);
                data = null;
                return len;
            }
        }
    }

    /**
     * Kills this revivable input stream. Makes current and future read calls
     * immediately return -1. The input stream may be revived through
     * {@link #resurrect()}. If this revivable input stream is already killed,
     * this method does nothing.
     * <p>
     * NOTE: this takes priority over {@link #queueReadException(Throwable)}
     *
     * @see #resurrect()
     * @see #revive()
     */
    public void kill() {
    	kill(null);
    }
    
    /**
     * Kills this revivable input stream.
     * <p>
     * If given throwable is non-null, then current and future reads will throw 
     * that exception; if given throwable is null, then current and future reads
     * immediately return -1 (EOF).
     * <p> 
     * The input stream may be revived through {@link #resurrect()}. If this 
     * revivable input stream is already killed, then this method can be used
     * to modify throwable that is being thrown / EOF. 
     * <p>
     * NOTE: this takes priority over {@link #queueReadException(Throwable)}
     *
     * @see #resurrect()
     * @see #revive()
     */
    public void kill(@Nullable Throwable e) {
        synchronized (dataLock) {
            killException = e;
            killed = true;
            dataLock.notifyAll();
        }
    }
    
    
    /**
     * Queues exception to be thrown during the read -- either the currently 
     * processing read or the next one.
     * <p>
     * The exception is thrown only once, then the reading is processed normally.
     * <p>
     * If multiple exceptions are added, they are thrown 'in order'.
     * <p>
     * NOTE: kill(..) settings take priority over this
     */
    public void queueReadException(Throwable e) throws IllegalStateException
    {
        synchronized (dataLock)
        {
        	exceptionQueue.add(e);
        	dataLock.notifyAll();
        }
    }

    /**
     * Same as {@link #resurrect()}
     */
    public synchronized void revive() {
        resurrect();
    }

    /**
     * Resurrects a killed revivable input stream. This makes it possible to
     * read from this input stream once again. If this revivable input stream is
     * not killed, this method does nothing.
     *
     * @see #kill()
     */
    public synchronized void resurrect() {
        killed = false;
    }

    private class ThreadReader implements Runnable {
        @Override
        public void run() {
        	boolean cleanExit = false;
        	Throwable uncaughtException = null;
        	try // Solf: add more robust error reporting
        	{
	            while (true) {
	                synchronized (dataLock) {
	                    requestData = false;
	                    dataLock.notifyAll();
	                    try {
	                        while (!requestData) {
	                            dataLock.wait();
	                        }
	                    }
	                    catch (InterruptedException ie) {
	                        workerThreadException = ie;
	                        cleanExit = true;
	                        return;
	                    }
	                }
	                // Data has been requested, create new array with data.
	                try {
	                    byte[] buffer = new byte[requestedBytes];
	                    int actualBytes = in.read(buffer); // <-- actual reading
	
	                    if (actualBytes == -1){
	                        synchronized (dataLock){
	                            streamClosed = true;
	                            dataLock.notifyAll();
		                        cleanExit = true;
	                            return;
	                        }
	                    }
	
	                    byte[] actual = new byte[actualBytes];
	                    System.arraycopy(buffer, 0, actual, 0, actualBytes);
	                    data = actual;
	                }
	                catch (IOException ioe) {
	                    synchronized (dataLock) {
	                        workerThreadException = ioe;
	                        dataLock.notifyAll();
	                        cleanExit = true;
	                        return;
	                    }
	                }
	            }
        	} catch (Throwable e)
        	{
        		uncaughtException = e;
        		cleanExit = false;
        	} finally
        	{
        		if (!cleanExit)
        		{
        			if (uncaughtException == null)
        				uncaughtException = new AssertionException("No 'success' and no exception in RevivableOutputStream worker thread.");
        			workerThreadException = uncaughtException;
        		}
        	}
        }
    }
    
    /**
     * Decorates an exception if {@link #decorateExceptions} is true; does nothing
     * (returns original exception) if false
     * <p>
     * If cloning/decoration fails, returns original exception
     */
    private <@Nonnull T extends Throwable> T decorateThrowableFallbackToOriginal(T throwable)
    {
    	if (isDecorateExceptions())
    		return TestUtil.cloneThrowableAddCurrentStackNoFailFallbackToOriginal(throwable);
    		
    	return throwable;
    }
}
