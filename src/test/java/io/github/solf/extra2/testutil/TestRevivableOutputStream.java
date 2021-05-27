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

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;

import java.awt.AWTError;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nonnull;
import javax.annotation.NonNullByDefault;

import org.javatuples.Pair;
import org.testng.annotations.Test;

import com.conversantmedia.util.concurrent.DisruptorBlockingQueue;

import io.github.solf.extra2.testutil.RevivableInputStream;
import io.github.solf.extra2.testutil.RevivableOutputStream;
import io.github.solf.extra2.testutil.TestUtil;
import io.github.solf.extra2.testutil.TestUtil.AsyncTestRunner;

/**
 * Tests for {@link RevivableOutputStream}
 *
 * @author Sergey Olefir
 */
@NonNullByDefault
public class TestRevivableOutputStream
{
	/** self-documenting */
	@Test
	public void testQueueExceptionForNextWrite() throws Exception
	{
		TestUtil.runWithTimeLimit(5000, () -> {
			try (
				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				RevivableOutputStream stream = new RevivableOutputStream(bos, 99))
			{
				stream.queueWriteException(new FileNotFoundException("intentional FileNotFoundException"));
				
				try
				{
					stream.write(1);
					assert false;
				} catch (FileNotFoundException e)
				{
					assert e.toString().contains("intentional FileNotFoundException") : e;
				}
				
				stream.write(1);
				stream.flush();
				
				assertEquals(bos.toByteArray().length, 1);
				assertEquals(bos.toByteArray()[0], 1);
			}
		});
	}

	/** self-documenting */
	@Test
	public void testQueueMultipleExceptionsForNextWrite() throws Exception
	{
		TestUtil.runWithTimeLimit(5000, () -> {
			try (
				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				RevivableOutputStream stream = new RevivableOutputStream(bos, 99))
			{
				stream.queueWriteException(new FileNotFoundException("intentional FileNotFoundException"));
				stream.queueWriteException(new AWTError("intentional AWTError"));
				stream.queueWriteException(new IllegalArgumentException("intentional IllegalArgumentException"));
				
				try
				{
					stream.write(1);
					assert false;
				} catch (FileNotFoundException e)
				{
					assert e.toString().contains("intentional FileNotFoundException") : e;
				}
				
				try
				{
					stream.write(2);
					assert false;
				} catch (AWTError e)
				{
					assert e.toString().contains("intentional AWTError") : e;
				}
				
				try
				{
					stream.write(3);
					assert false;
				} catch (IllegalArgumentException e)
				{
					assert e.toString().contains("intentional IllegalArgumentException") : e;
				}
				
				stream.write(4);
				stream.flush();
				
				assertEquals(bos.toByteArray().length, 1);
				assertEquals(bos.toByteArray()[0], 4);
			}
		});
	}
	
	/** self-documenting */
	@Test
	public void testQueueExceptionDuringWrite() throws Exception
	{
		TestUtil.runWithTimeLimit(5000, () -> {
			
			Pair<RevivableInputStream, RevivableOutputStream> pipe = TestUtilPipes.createKillableBytePipe(5);
			try (
				RevivableOutputStream stream = pipe.getValue1();
				RevivableInputStream input = pipe.getValue0())
			{
				AsyncTestRunner<Void> asyncFuture = TestUtil.runAsynchronously(() -> {
					Thread.sleep(2000);
					stream.queueWriteException(new SocketTimeoutException("intentional SocketTimeoutException"));
				});
				
				long start = System.currentTimeMillis();
				try
				{
					for (int i = 0; i < 20; i++)
						stream.write(i); // try to write more bytes than there's space in buffer
					assert false;
				} catch (SocketTimeoutException e)
				{
					assert e.toString().contains("intentional SocketTimeoutException") : e;
				}
				long duration = System.currentTimeMillis() - start;
				assert duration > 1000 : duration;
				assert duration < 3000 : duration;
				
				AsyncTestRunner<Void> asyncFuture2 = TestUtil.runAsynchronously(() -> {
					// write and flush asynchronously
					stream.write(100);
					stream.flush();
				});
				
				int count = 0;
				while(true)
				{
					int next = input.read();
					count++;
					if (next == 100)
						break;
				}
				assert count < 21 : count;
				
				asyncFuture.getResult(500); // will throw exception if there's any problem in async thread
				asyncFuture2.getResult(500);
			}
		});
	}
	
	/** self-documenting */
	@Test
	public void testKillForNextWriteAndChangingKillException() throws Exception
	{
		TestUtil.runWithTimeLimit(5000, () -> {
			try (
				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				RevivableOutputStream stream = new RevivableOutputStream(bos, 99))
			{
				stream.kill(); // kill with no exception
				for (int i = 0; i < 3; i++)
				{
					try
					{
						stream.write(i);
						assert false;
					} catch (IOException e)
					{
						assert e.toString().contains("java.io.IOException: Stream [temporarily] killed") : e;
					}
				}
				
				stream.revive();
				stream.write(4);
				stream.flush();
				assertEquals(bos.toByteArray().length, 1);
				assertEquals(bos.toByteArray()[0], 4);
				
				stream.kill(new SocketTimeoutException("intentional SocketTimeoutException")); // kill with exception
				for (int i = 0; i < 5; i++)
				{
					try
					{
						stream.write(10 + i);
						assert false;
					} catch (SocketTimeoutException e)
					{
						assert e.toString().contains("intentional SocketTimeoutException") : e;
					}
				}
				
				stream.kill(new AWTError("intentional AWTError")); // change exception
				try
				{
					stream.write(21);
					assert false;
				} catch (AWTError e)
				{
					assert e.toString().contains("intentional AWTError") : e;
				}
				
				stream.kill(new IllegalArgumentException("intentional IllegalArgumentException")); // change exception
				try
				{
					stream.write(31);
					assert false;
				} catch (IllegalArgumentException e)
				{
					assert e.toString().contains("intentional IllegalArgumentException") : e;
				}
				
				
				stream.resurrect();
				stream.write(44);
				stream.flush();
				assertEquals(bos.toByteArray().length, 2);
				assertEquals(bos.toByteArray()[1], 44);
			}
		});
	}
	
	/** self-documenting */
	@Test
	public void testKillPriorityOverQueuedException() throws Exception
	{
		TestUtil.runWithTimeLimit(5000, () -> {
			try (
				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				RevivableOutputStream stream = new RevivableOutputStream(bos, 99))
			{
				stream.queueWriteException(new AWTError("intentional AWTError"));
				
				stream.kill(); // kill with no exception
				for (int i = 0; i < 3; i++) // kill takes priority
				{
					try
					{
						stream.write(i);
						assert false;
					} catch (IOException e)
					{
						assert e.toString().contains("java.io.IOException: Stream [temporarily] killed") : e;
					}
				}
				
				stream.kill(new SocketTimeoutException("intentional SocketTimeoutException")); // kill with exception takes priority
				for (int i = 0; i < 5; i++)
				{
					try
					{
						stream.write(10 + i);
						assert false;
					} catch (SocketTimeoutException e)
					{
						assert e.toString().contains("intentional SocketTimeoutException") : e;
					}
				}
				
				stream.revive(); // un-kill
				
				try
				{
					stream.write(22);
					assert false;
				} catch (AWTError e)
				{
					assert e.toString().contains("intentional AWTError") : e;
				}
				
				stream.write(44);
				stream.flush();
				assertEquals(bos.toByteArray().length, 1);
				assertEquals(bos.toByteArray()[0], 44);
			}
		});
	}
	
	/** self-documenting */
	@Test
	public void testKillNoExceptionDuringWrite() throws Exception
	{
		TestUtil.runWithTimeLimit(5000, () -> {
			
			Pair<RevivableInputStream, RevivableOutputStream> pipe = TestUtilPipes.createKillableBytePipe(5);
			try (
				RevivableOutputStream stream = pipe.getValue1();
				RevivableInputStream input = pipe.getValue0())
			{
				AsyncTestRunner<Void> asyncFuture = TestUtil.runAsynchronously(() -> {
					Thread.sleep(2000);
					stream.kill();
				});
				
				long start = System.currentTimeMillis();
				try
				{
					for (int i = 0; i < 20; i++)
						stream.write(i); // overflow buffers
					assert false;
				} catch (IOException e)
				{
					assert e.toString().contains("java.io.IOException: Stream [temporarily] killed") : e;
				}
				long duration = System.currentTimeMillis() - start;
				assert duration > 1000 : duration;
				assert duration < 3000 : duration;
				
				stream.revive();
				
				AsyncTestRunner<Void> asyncFuture2 = TestUtil.runAsynchronously(() -> {
					// write and flush asynchronously
					stream.write(100);
					stream.flush();
				});
				
				int count = 0;
				while(true)
				{
					int next = input.read();
					count++;
					if (next == 100)
						break;
				}
				assert count < 21 : count;
				
				asyncFuture.getResult(500); // will throw exception if there's any problem in async thread
				asyncFuture2.getResult(500);
			}
		});
	}
	
	/** self-documenting */
	@Test
	public void testKillWithExceptionDuringWrite() throws Exception
	{
		TestUtil.runWithTimeLimit(5000, () -> {
			
			Pair<RevivableInputStream, RevivableOutputStream> pipe = TestUtilPipes.createKillableBytePipe(5);
			try (
				RevivableOutputStream stream = pipe.getValue1();
				RevivableInputStream input = pipe.getValue0())
			{
				AsyncTestRunner<Void> asyncFuture = TestUtil.runAsynchronously(() -> {
					Thread.sleep(2000);
					stream.kill(new SocketTimeoutException("intentional SocketTimeoutException"));
				});
				
				long start = System.currentTimeMillis();
				try
				{
					for (int i = 0; i < 20; i++)
						stream.write(i); // overflow buffers
					assert false;
				} catch (IOException e)
				{
					assert e.toString().contains("intentional SocketTimeoutException") : e;
				}
				long duration = System.currentTimeMillis() - start;
				assert duration > 1000 : duration;
				assert duration < 3000 : duration;
				
				stream.revive();
				
				AsyncTestRunner<Void> asyncFuture2 = TestUtil.runAsynchronously(() -> {
					// write and flush asynchronously
					stream.write(100);
					stream.flush();
				});
				
				int count = 0;
				while(true)
				{
					int next = input.read();
					count++;
					if (next == 100)
						break;
				}
				assert count < 21 : count;
				
				asyncFuture.getResult(500); // will throw exception if there's any problem in async thread
				asyncFuture2.getResult(500);
			}
		});
	}

	/** self-documenting */
	@Test
	public void testFlush() throws Exception
	{
		final AtomicInteger flushesCount = new AtomicInteger(0);
		
		TestUtil.runWithTimeLimit(5000, () -> {
			try (
				ByteArrayOutputStream bos = new ByteArrayOutputStream()
				{
					/* (non-Javadoc)
					 * @see java.io.OutputStream#flush()
					 */
					@Override
					public void flush()
						throws IOException
					{
						super.flush();
						flushesCount.incrementAndGet();
					}
				};
				RevivableOutputStream stream = new RevivableOutputStream(bos, 99))
			{
				stream.write(1);
				stream.write(2);
				assertEquals(flushesCount.get(), 0);
				//assertEquals(bos.toByteArray().length, 0); // This is not guaranteed, may write w/o flushing
				
				stream.flush();
				assertEquals(bos.toByteArray().length, 2);
				assertEquals(bos.toByteArray()[0], 1);
				assertEquals(bos.toByteArray()[1], 2);
				assertEquals(flushesCount.get(), 1);
				
				stream.write(3);
				//assertEquals(bos.toByteArray().length, 3); // This is not guaranteed, may write w/o flushing
				assertEquals(bos.toByteArray()[0], 1);
				assertEquals(bos.toByteArray()[1], 2);
				assertEquals(flushesCount.get(), 1);
				
				stream.flush();
				assertEquals(flushesCount.get(), 2);
				assertEquals(bos.toByteArray().length, 3);
				assertEquals(bos.toByteArray()[0], 1);
				assertEquals(bos.toByteArray()[1], 2);
				assertEquals(bos.toByteArray()[2], 3);
			}
		});
		
		assertEquals(flushesCount.get(), 3); // 3rd is done by close
	}

	/** self-documenting */
	@Test
	public void testCloseAndFlushAfterClose() throws Exception
	{
		final AtomicBoolean outputClosed = new AtomicBoolean(false);
		
		TestUtil.runWithTimeLimit(5000, () -> {
			try (
				ByteArrayOutputStream bos = new ByteArrayOutputStream()
				{
					/* (non-Javadoc)
					 * @see java.io.ByteArrayOutputStream#close()
					 */
					@Override
					public void close()
						throws IOException
					{
						super.close();
						outputClosed.set(true);
					}
				};
				RevivableOutputStream stream = new RevivableOutputStream(bos, 99))
			{
				stream.write(1);
				stream.write(2);
				//assertEquals(bos.toByteArray().length, 0); // This is not guaranteed, may write w/o flushing
				assertEquals(outputClosed.get(), false);
				
				stream.close();
				assertEquals(outputClosed.get(), true);
				assertEquals(bos.toByteArray().length, 2);
				assertEquals(bos.toByteArray()[0], 1);
				assertEquals(bos.toByteArray()[1], 2);
				
				stream.close(); // does nothing now
				stream.close();
				
				try
				{
					stream.write(3);
				} catch (IOException e)
				{
					assert e.toString().contains("java.io.IOException: Stream Closed") : e;
				}
				
				try
				{
					stream.flush();
				} catch (IOException e)
				{
					assert e.toString().contains("java.io.IOException: Stream Closed") : e;
				}
			}
		});
	}
	
	/** self-documenting */
	@Test
	public void testSmallBuffers() throws Exception
	{
		TestUtil.runWithTimeLimit(5000, () -> {
			
			AtomicInteger counter = new AtomicInteger(0);
			AtomicBoolean flushStarted = new AtomicBoolean(false);
			AtomicBoolean flushFinished = new AtomicBoolean(false);
			
			Pair<RevivableInputStream, RevivableOutputStream> pipe = TestUtilPipes.createKillableBytePipe(5);
			try (
				RevivableOutputStream stream = pipe.getValue1();
				RevivableInputStream input = pipe.getValue0())
			{
				AsyncTestRunner<Void> asyncFuture = TestUtil.runAsynchronously(() -> {
					for (int i = 0; i < 23; i++) // non-multiple of buffer size
					{
						stream.write(i);
						counter.incrementAndGet();
					}
					
					flushStarted.set(true);
					stream.flush();
					flushFinished.set(true);
					
					for (int i = 23; i < 50; i++)
					{
						stream.write(i);
						counter.incrementAndGet();
					}
					
					stream.flush();
				});
				
				Thread.sleep(1000);
				{
					int cnt = counter.get();
					assert cnt >= 5 : cnt; // between 1 and 3 times the buffer size
					assert cnt < 20 : cnt;
				}
				
				// Track current byte
				int current = 0;
				// Read slowly until flush is initiated
				assertEquals(flushStarted.get(), false);
				while(!flushStarted.get())
				{
					assertEquals(input.read(), current++);
					Thread.sleep(100);
				}
				assert current < 23 : current; // check that we didn't read past the flush
				// Flush started, make sure it is 'stuck' in the buffer
				Thread.sleep(1000);
				assertEquals(flushFinished.get(), false);
				while(current < 30)
					assertEquals(input.read(), current++);
				assertEquals(flushFinished.get(), true); // flush must've finished by now

				// read the rest of the data
				while(current < 50)
					assertEquals(input.read(), current++);
				
				asyncFuture.getResult(500); // will throw exception if there's any problem in async thread
			}
		});
	}

	/** self-documenting */
	@Test
	public void testWorkerThreadExit() throws Exception
	{
		TestUtil.runWithTimeLimit(5000, () -> {
			try (
				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				RevivableOutputStream stream = new RevivableOutputStream(bos, 99))
			{
				Thread workerThread = TestUtil.getInaccessibleFieldValue(RevivableOutputStream.class, "workerThread", stream);
				
				stream.write(1);
				stream.write(2);
				//assertEquals(bos.toByteArray().length, 0); // This is not guaranteed, may write w/o flushing
				assertEquals(workerThread.isAlive(), true);
				
				stream.flush();
				assertEquals(bos.toByteArray().length, 2);
				assertEquals(bos.toByteArray()[0], 1);
				assertEquals(bos.toByteArray()[1], 2);
				assertEquals(workerThread.isAlive(), true);
				
				stream.close();
				workerThread.join(1000);
				assertEquals(workerThread.isAlive(), false);
			}
		});
	}
	
	/** self-documenting */
	@Test()
	public void testUnderlyingStreamFailureDuringFlush() throws Exception
	{
		TestUtil.runWithTimeLimit(5000, () -> {
			
			AtomicInteger counter = new AtomicInteger(0);
			AtomicBoolean flushStarted = new AtomicBoolean(false);
			AtomicBoolean flushFinished = new AtomicBoolean(false);
			
			Pair<RevivableInputStream, RevivableOutputStream> pipe = TestUtilPipes.createKillableBytePipe(4);
			try (
				RevivableInputStream input = pipe.getValue0())
			{
				// Those are not managed because they'll crash on auto-close
				@SuppressWarnings("resource") RevivableOutputStream underlyingStream = pipe.getValue1();
				@SuppressWarnings("resource") RevivableOutputStream stream = new RevivableOutputStream(underlyingStream, 4);
				
				
				AsyncTestRunner<Void> asyncFuture = TestUtil.runAsynchronously(() -> {
					for (int i = 0; i < 23; i++) // non-multiple of buffer size
					{
						stream.write(i);
						counter.incrementAndGet();
					}
					
					flushStarted.set(true);
					stream.flush();
					flushFinished.set(true);
					
					for (int i = 23; i < 50; i++)
					{
						stream.write(i);
						counter.incrementAndGet();
					}
					
					stream.flush();
				});
				
				// Track current byte
				int current = 0;
				// Read slowly until flush is initiated
				assertEquals(flushStarted.get(), false);
				while(!flushStarted.get())
				{
					assertEquals(input.read(), current++);
					Thread.sleep(100);
				}
				assert current < 23 : current; // check that we didn't read past the flush
				// Flush started, make sure it is 'stuck' in the buffer
				Thread.sleep(1000);
				assertEquals(flushFinished.get(), false);
				
				// Now crash underlying stream
				underlyingStream.kill(new SocketTimeoutException("intentional SocketTimeoutException"));
				
				try
				{
					asyncFuture.getResult(2000); // will throw exception if there's any problem in async thread
					assert false;
				} catch (ExecutionException e)
				{
					Throwable cause = e.getCause();
					assert cause instanceof SocketTimeoutException : e;
					assert cause.toString().contains("intentional SocketTimeoutException") : e;
				}
				
				// Make sure flush hasn't completed
				assertEquals(flushFinished.get(), false);
			}
		});
	}
	
	/** self-documenting */
	@Test()
	public void testUnderlyingStreamFailureDuringWrite() throws Exception
	{
		TestUtil.runWithTimeLimit(5000, () -> {
			
			AtomicInteger counter = new AtomicInteger(0);
			Pair<RevivableInputStream, RevivableOutputStream> pipe = TestUtilPipes.createKillableBytePipe(4);
			try (
				RevivableInputStream input = pipe.getValue0())
			{
				// Those are not managed because they'll crash on auto-close
				@SuppressWarnings("resource") RevivableOutputStream underlyingStream = pipe.getValue1();
				@SuppressWarnings("resource") RevivableOutputStream stream = new RevivableOutputStream(underlyingStream, 4);
				
				
				AsyncTestRunner<Void> asyncFuture = TestUtil.runAsynchronously(() -> {
					for (int i = 0; i < 50; i++)
					{
						stream.write(i);
						counter.incrementAndGet();
					}
				});
				
				Thread.sleep(1000); // wait for buffers to fill
				
				assert asyncFuture.getThread().isAlive();
				
				// Now crash underlying stream
				underlyingStream.kill(new SocketTimeoutException("intentional SocketTimeoutException"));
				
				try
				{
					asyncFuture.getResult(2000); // will throw exception if there's any problem in async thread
					assert false;
				} catch (ExecutionException e)
				{
					Throwable cause = e.getCause();
					assert cause instanceof SocketTimeoutException : e;
					assert cause.toString().contains("intentional SocketTimeoutException") : e;
				}
			}
		});
	}
	
	/** self-documenting */
	@Test()
	public void testKillWithExceptionDuringFlush() throws Exception
	{
		TestUtil.runWithTimeLimit(5000, () -> {
			
			AtomicInteger counter = new AtomicInteger(0);
			AtomicBoolean flushStarted = new AtomicBoolean(false);
			AtomicBoolean flushFinished = new AtomicBoolean(false);
			
			Pair<RevivableInputStream, RevivableOutputStream> pipe = TestUtilPipes.createKillableBytePipe(4);
			try (
				RevivableInputStream input = pipe.getValue0())
			{
				// Those are not managed because they'll crash on auto-close
				@SuppressWarnings("resource") RevivableOutputStream stream = pipe.getValue1();
				
				AsyncTestRunner<Void> asyncFuture = TestUtil.runAsynchronously(() -> {
					for (int i = 0; i < 23; i++) // non-multiple of buffer size
					{
						stream.write(i);
						counter.incrementAndGet();
					}
					
					flushStarted.set(true);
					stream.flush();
					flushFinished.set(true);
					
					for (int i = 23; i < 50; i++)
					{
						stream.write(i);
						counter.incrementAndGet();
					}
					
					stream.flush();
				});
				
				// Track current byte
				int current = 0;
				// Read slowly until flush is initiated
				assertEquals(flushStarted.get(), false);
				while(!flushStarted.get())
				{
					assertEquals(input.read(), current++);
					Thread.sleep(100);
				}
				assert current < 23 : current; // check that we didn't read past the flush
				// Flush started, make sure it is 'stuck' in the buffer
				Thread.sleep(1000);
				assertEquals(flushFinished.get(), false);
				
				// Now crash underlying stream
				stream.kill(new SocketTimeoutException("intentional SocketTimeoutException"));
				
				try
				{
					asyncFuture.getResult(2000); // will throw exception if there's any problem in async thread
					assert false;
				} catch (ExecutionException e)
				{
					Throwable cause = e.getCause();
					assert cause instanceof SocketTimeoutException : e;
					assert cause.toString().contains("intentional SocketTimeoutException") : e;
				}
				
				// Make sure flush hasn't completed
				assertEquals(flushFinished.get(), false);
			}
		});
	}
	
	/** self-documenting */
	@Test()
	public void testKillNoExceptionDuringFlush() throws Exception
	{
		TestUtil.runWithTimeLimit(5000, () -> {
			
			AtomicInteger counter = new AtomicInteger(0);
			AtomicBoolean flushStarted = new AtomicBoolean(false);
			AtomicBoolean flushFinished = new AtomicBoolean(false);
			
			Pair<RevivableInputStream, RevivableOutputStream> pipe = TestUtilPipes.createKillableBytePipe(4);
			try (
				RevivableInputStream input = pipe.getValue0())
			{
				// Those are not managed because they'll crash on auto-close
				@SuppressWarnings("resource") RevivableOutputStream stream = pipe.getValue1();
				
				AsyncTestRunner<Void> asyncFuture = TestUtil.runAsynchronously(() -> {
					for (int i = 0; i < 23; i++) // non-multiple of buffer size
					{
						stream.write(i);
						counter.incrementAndGet();
					}
					
					flushStarted.set(true);
					stream.flush();
					flushFinished.set(true);
					
					for (int i = 23; i < 50; i++)
					{
						stream.write(i);
						counter.incrementAndGet();
					}
					
					stream.flush();
				});
				
				// Track current byte
				int current = 0;
				// Read slowly until flush is initiated
				assertEquals(flushStarted.get(), false);
				while(!flushStarted.get())
				{
					assertEquals(input.read(), current++);
					Thread.sleep(100);
				}
				assert current < 23 : current; // check that we didn't read past the flush
				// Flush started, make sure it is 'stuck' in the buffer
				Thread.sleep(1000);
				assertEquals(flushFinished.get(), false);
				
				// Now crash underlying stream
				stream.kill();
				
				try
				{
					asyncFuture.getResult(2000); // will throw exception if there's any problem in async thread
					assert false;
				} catch (ExecutionException e)
				{
					Throwable cause = e.getCause();
					assert cause instanceof IOException : e;
					assert cause.toString().contains("Stream [temporarily] killed") : e;
				}
				
				// Make sure flush hasn't completed
				assertEquals(flushFinished.get(), false);
			}
		});
	}
	
	/** self-documenting */
	@Test()
	public void testQueueExceptionDuringFlush() throws Exception
	{
		TestUtil.runWithTimeLimit(5000, () -> {
			
			AtomicInteger counter = new AtomicInteger(0);
			AtomicBoolean flushStarted = new AtomicBoolean(false);
			AtomicBoolean flushFinished = new AtomicBoolean(false);
			
			Pair<RevivableInputStream, RevivableOutputStream> pipe = TestUtilPipes.createKillableBytePipe(4);
			try (
				RevivableInputStream input = pipe.getValue0())
			{
				// Those are not managed because they'll crash on auto-close
				@SuppressWarnings("resource") RevivableOutputStream stream = pipe.getValue1();
				
				AsyncTestRunner<Void> asyncFuture = TestUtil.runAsynchronously(() -> {
					for (int i = 0; i < 23; i++) // non-multiple of buffer size
					{
						stream.write(i);
						counter.incrementAndGet();
					}
					
					flushStarted.set(true);
					stream.flush();
					flushFinished.set(true);
					
					for (int i = 23; i < 50; i++)
					{
						stream.write(i);
						counter.incrementAndGet();
					}
					
					stream.flush();
				});
				
				// Track current byte
				int current = 0;
				// Read slowly until flush is initiated
				assertEquals(flushStarted.get(), false);
				while(!flushStarted.get())
				{
					assertEquals(input.read(), current++);
					Thread.sleep(100);
				}
				assert current < 23 : current; // check that we didn't read past the flush
				// Flush started, make sure it is 'stuck' in the buffer
				Thread.sleep(1000);
				assertEquals(flushFinished.get(), false);
				
				// Now queue exception
				stream.queueWriteException(new SocketTimeoutException("intentional SocketTimeoutException"));
				
				try
				{
					asyncFuture.getResult(2000); // will throw exception if there's any problem in async thread
					assert false;
				} catch (ExecutionException e)
				{
					Throwable cause = e.getCause();
					assert cause instanceof SocketTimeoutException : e;
					assert cause.toString().contains("intentional SocketTimeoutException") : e;
				}
				
				// Make sure flush hasn't completed
				assertEquals(flushFinished.get(), false);
			}
		});
	}
	
	/** self-documenting */
	@Test()
	public void testInterruptDuringFlush() throws Exception
	{
		TestUtil.runWithTimeLimit(5000, () -> {
			
			AtomicInteger counter = new AtomicInteger(0);
			AtomicBoolean flushStarted = new AtomicBoolean(false);
			AtomicBoolean flushFinished = new AtomicBoolean(false);
			
			Pair<RevivableInputStream, RevivableOutputStream> pipe = TestUtilPipes.createKillableBytePipe(4);
			try (
				RevivableInputStream input = pipe.getValue0())
			{
				// Those are not managed because they'll crash on auto-close
				@SuppressWarnings("resource") RevivableOutputStream stream = pipe.getValue1();
				
				AsyncTestRunner<Void> asyncFuture = TestUtil.runAsynchronously(() -> {
					for (int i = 0; i < 23; i++) // non-multiple of buffer size
					{
						stream.write(i);
						counter.incrementAndGet();
					}
					
					flushStarted.set(true);
					stream.flush();
					flushFinished.set(true);
					
					for (int i = 23; i < 50; i++)
					{
						stream.write(i);
						counter.incrementAndGet();
					}
					
					stream.flush();
				});
				
				// Track current byte
				int current = 0;
				// Read slowly until flush is initiated
				assertEquals(flushStarted.get(), false);
				while(!flushStarted.get())
				{
					assertEquals(input.read(), current++);
					Thread.sleep(100);
				}
				assert current < 23 : current; // check that we didn't read past the flush
				// Flush started, make sure it is 'stuck' in the buffer
				Thread.sleep(1000);
				assertEquals(flushFinished.get(), false);
				
				// Interrupt
				asyncFuture.getThread().interrupt();
				
				
				try
				{
					asyncFuture.getResult(2000); // will throw exception if there's any problem in async thread
					assert false;
				} catch (ExecutionException e)
				{
					Throwable cause = e.getCause();
					assert cause instanceof InterruptedIOException : e;
					assert cause.toString().contains("java.io.InterruptedIOException: java.lang.InterruptedException") : e;
				}
				
				// Make sure flush hasn't completed
				assertEquals(flushFinished.get(), false);
			}
		});
	}
	
	/** self-documenting */
	@Test()
	public void testInterruptDuringWrite() throws Exception
	{
		TestUtil.runWithTimeLimit(5000, () -> {
			
			AtomicInteger counter = new AtomicInteger(0);
			Pair<RevivableInputStream, RevivableOutputStream> pipe = TestUtilPipes.createKillableBytePipe(4);
			try (
				RevivableInputStream input = pipe.getValue0())
			{
				// Those are not managed because they'll crash on auto-close
				@SuppressWarnings("resource") RevivableOutputStream stream = pipe.getValue1();
				
				
				AsyncTestRunner<Void> asyncFuture = TestUtil.runAsynchronously(() -> {
					for (int i = 0; i < 50; i++)
					{
						stream.write(i);
						counter.incrementAndGet();
					}
				});
				
				Thread.sleep(1000); // wait for buffers to fill
				
				assert asyncFuture.getThread().isAlive();
				
				// Now interrupt
				asyncFuture.getThread().interrupt();
				
				try
				{
					asyncFuture.getResult(2000); // will throw exception if there's any problem in async thread
					assert false;
				} catch (ExecutionException e)
				{
					Throwable cause = e.getCause();
					assert cause instanceof InterruptedIOException : e;
					assert cause.toString().contains("java.io.InterruptedIOException: java.lang.InterruptedException") : e;
				}
			}
		});
	}
	
	
	/** self-documenting */
	@Test()
	public void testExceptionDecoration() throws Exception
	{
		TestUtil.runWithTimeLimit(5000, () -> {
			
			Pair<RevivableInputStream, RevivableOutputStream> pipe = TestUtilPipes.createKillableBytePipe(4);
			try (
				RevivableOutputStream stream = pipe.getValue1();
				RevivableInputStream input = pipe.getValue0())
			{
				stream.setDecorateExceptions(true);
				
				SocketTimeoutException origException = new SocketTimeoutException("intentional SocketTimeoutException");
				stream.queueWriteException(origException);
				
				try
				{
					stream.write(1);
					assert false;
				} catch (Exception e)
				{
					assert e instanceof SocketTimeoutException : e;
					assert e.toString().contains("intentional SocketTimeoutException") : e;
					assertNotEquals(e, origException);
					assertEquals(e.getCause(), origException);
					assert e.getStackTrace()[0].toString().contains("cloneThrowableAddCurrentStack") : e;
				}
			}
		});
	}
	
	/** self-documenting */
	@Test()
	public void testExceptionNoDecoration() throws Exception
	{
		TestUtil.runWithTimeLimit(5000, () -> {
			
			Pair<RevivableInputStream, RevivableOutputStream> pipe = TestUtilPipes.createKillableBytePipe(4);
			try (
				RevivableOutputStream stream = pipe.getValue1();
				RevivableInputStream input = pipe.getValue0())
			{
				stream.setDecorateExceptions(false);
				
				SocketTimeoutException origException = new SocketTimeoutException("intentional SocketTimeoutException");
				stream.queueWriteException(origException);
				
				try
				{
					stream.write(1);
					assert false;
				} catch (Exception e)
				{
					assertEquals(e, origException);
				}
			}
		});
	}
	
	/** self-documenting */
	@Test()
	public void testConcurrentFlushes() throws Exception
	{
		TestUtil.runWithTimeLimit(10_000, () -> {
			{
				// without 'try' block as we'll break this stream
				@SuppressWarnings("resource") RevivableOutputStream stream = new RevivableOutputStream(mock(OutputStream.class), 100);
				// Kill worker thread so that noone interferes with this test
				Thread workerThread = TestUtil.getInaccessibleFieldValue(RevivableOutputStream.class, "workerThread", stream);
				workerThread.interrupt(); 
				workerThread.join(1000);
				assert !workerThread.isAlive() : workerThread;
				
				// Reset 'failure' flag
				TestUtil.setInaccessibleFieldValue(RevivableOutputStream.class, "transferThreadException", stream, null);
				
				// Get the queue.
				DisruptorBlockingQueue<Object> queue = TestUtil.getInaccessibleFieldValue(
					RevivableOutputStream.class, "flushResponsesQueue", stream);
				queue.clear();
				
				AsyncTestRunner<?> asyncThreads[] = new @Nonnull AsyncTestRunner[5];
				for (int i = 0; i < asyncThreads.length; i++)
				{
					asyncThreads[i] = TestUtil.runAsynchronously(() -> {
						stream.flush();
					});
				}
				
				for (int i = asyncThreads.length; i > 0 ; i--)
				{
					{
						int alive = 0;
						for (AsyncTestRunner<?> a : asyncThreads)
						{
							if (a.getThread().isAlive())
								alive++;
						}
						assertEquals(alive, i);
					}
					
					// Now 'exit' one of the threads.
					queue.offer(new SocketTimeoutException("intentional SocketTimeoutException"));
					Thread.sleep(1000); // Wait for some thread to exit.
					
					{
						int alive = 0;
						for (AsyncTestRunner<?> a : asyncThreads)
						{
							if (a.getThread().isAlive())
								alive++;
						}
						assertEquals(alive, i - 1);
					}
				}
				
				for (AsyncTestRunner<?> a : asyncThreads)
				{
					try
					{
						a.getResult(1000);
						assert false;
					} catch (ExecutionException e)
					{
						Throwable cause = e.getCause();
						assert cause instanceof SocketTimeoutException : e;
						assert cause.toString().contains("intentional SocketTimeoutException") : e;
					}
				}
			}
		});
	}
}
