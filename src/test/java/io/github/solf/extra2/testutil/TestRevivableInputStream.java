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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;

import java.awt.AWTError;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.net.SocketTimeoutException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.javatuples.Pair;
import org.testng.annotations.Test;

import io.github.solf.extra2.testutil.TestUtil.AsyncTestRunner;

/**
 * Tests for {@link RevivableInputStream}
 *
 * @author Sergey Olefir
 */
@NonNullByDefault
public class TestRevivableInputStream
{
	/** self-documenting */
	@Test
	public void testQueueExceptionForNextRead() throws Exception
	{
		TestUtil.runWithTimeLimit(5000, () -> {
			try (RevivableInputStream stream = new RevivableInputStream(new ByteArrayInputStream(new byte[] {1, 2, 3})))
			{
				stream.queueReadException(new FileNotFoundException("intentional FileNotFoundException"));
				
				try
				{
					stream.read();
					assert false;
				} catch (FileNotFoundException e)
				{
					assert e.toString().contains("intentional FileNotFoundException") : e;
				}
				
				assertEquals(stream.read(), 1);
			}
		});
	}

	/** self-documenting */
	@Test
	public void testQueueMultipleExceptionsForNextRead() throws Exception
	{
		TestUtil.runWithTimeLimit(5000, () -> {
			try (RevivableInputStream stream = new RevivableInputStream(new ByteArrayInputStream(new byte[] {1, 2, 3})))
			{
				stream.queueReadException(new FileNotFoundException("intentional FileNotFoundException"));
				stream.queueReadException(new AWTError("intentional AWTError"));
				stream.queueReadException(new IllegalArgumentException("intentional IllegalArgumentException"));
				
				try
				{
					stream.read();
					assert false;
				} catch (FileNotFoundException e)
				{
					assert e.toString().contains("intentional FileNotFoundException") : e;
				}
				
				try
				{
					stream.read();
					assert false;
				} catch (AWTError e)
				{
					assert e.toString().contains("intentional AWTError") : e;
				}
				
				try
				{
					stream.read();
					assert false;
				} catch (IllegalArgumentException e)
				{
					assert e.toString().contains("intentional IllegalArgumentException") : e;
				}
				
				assertEquals(stream.read(), 1);
			}
		});
	}
	
	/** self-documenting */
	@Test
	public void testQueueExceptionDuringRead() throws Exception
	{
		TestUtil.runWithTimeLimit(5000, () -> {
			
			Pair<RevivableInputStream, RevivableOutputStream> pipe = TestUtilPipes.createKillableBytePipe(100);
			try (RevivableInputStream stream = pipe.getValue0())
			{
				AsyncTestRunner<Void> asyncFuture = TestUtil.runAsynchronously(() -> {
					Thread.sleep(2000);
					stream.queueReadException(new SocketTimeoutException("intentional SocketTimeoutException"));
				});
				
				long start = System.currentTimeMillis();
				try
				{
					stream.read();
					assert false;
				} catch (SocketTimeoutException e)
				{
					assert e.toString().contains("intentional SocketTimeoutException") : e;
				}
				long duration = System.currentTimeMillis() - start;
				assert duration > 1000 : duration;
				assert duration < 3000 : duration;
				
				pipe.getValue1().write(1);
				assertEquals(stream.read(), 1);
				
				asyncFuture.getResult(500); // will throw exception if there's any problem in async thread
			}
		});
	}
	
	/** self-documenting */
	@Test
	public void testKillForNextReadAndChangingKillException() throws Exception
	{
		TestUtil.runWithTimeLimit(5000, () -> {
			try (RevivableInputStream stream = new RevivableInputStream(new ByteArrayInputStream(new byte[] {1, 2, 3})))
			{
				stream.kill(); // kill with no exception
				assert stream.read() < 0;
				assert stream.read() < 0;
				assert stream.read() < 0;
				
				stream.revive();
				assertEquals(stream.read(), 1);
				
				stream.kill(new SocketTimeoutException("intentional SocketTimeoutException")); // kill with exception
				for (int i = 0; i < 5; i++)
				{
					try
					{
						stream.read();
						assert false;
					} catch (SocketTimeoutException e)
					{
						assert e.toString().contains("intentional SocketTimeoutException") : e;
					}
				}
				
				stream.kill(new AWTError("intentional AWTError")); // change exception
				try
				{
					stream.read();
					assert false;
				} catch (AWTError e)
				{
					assert e.toString().contains("intentional AWTError") : e;
				}
				
				stream.kill(new IllegalArgumentException("intentional IllegalArgumentException")); // change exception
				try
				{
					stream.read();
					assert false;
				} catch (IllegalArgumentException e)
				{
					assert e.toString().contains("intentional IllegalArgumentException") : e;
				}
				
				
				stream.resurrect();
				assertEquals(stream.read(), 2);
			}
		});
	}
	
	/** self-documenting */
	@Test
	public void testKillPriorityOverQueuedException() throws Exception
	{
		TestUtil.runWithTimeLimit(5000, () -> {
			try (RevivableInputStream stream = new RevivableInputStream(new ByteArrayInputStream(new byte[] {1, 2, 3})))
			{
				stream.queueReadException(new AWTError("intentional AWTError"));
				
				stream.kill(); // kill with no exception
				assert stream.read() < 0; // kill takes priority
				assert stream.read() < 0;
				
				stream.kill(new SocketTimeoutException("intentional SocketTimeoutException")); // kill with exception takes priority
				for (int i = 0; i < 5; i++)
				{
					try
					{
						stream.read();
						assert false;
					} catch (SocketTimeoutException e)
					{
						assert e.toString().contains("intentional SocketTimeoutException") : e;
					}
				}
				
				stream.revive(); // un-kill
				
				try
				{
					stream.read();
					assert false;
				} catch (AWTError e)
				{
					assert e.toString().contains("intentional AWTError") : e;
				}
				
				assertEquals(stream.read(), 1);
			}
		});
	}
	
	/** self-documenting */
	@Test
	public void testKillNoExceptionDuringRead() throws Exception
	{
		TestUtil.runWithTimeLimit(5000, () -> {
			
			Pair<RevivableInputStream, RevivableOutputStream> pipe = TestUtilPipes.createKillableBytePipe(100);
			try (RevivableInputStream stream = pipe.getValue0())
			{
				AsyncTestRunner<Void> asyncFuture = TestUtil.runAsynchronously(() -> {
					Thread.sleep(2000);
					stream.kill();
				});
				
				long start = System.currentTimeMillis();
				assertEquals(stream.read(), -1);
				long duration = System.currentTimeMillis() - start;
				assert duration > 1000 : duration;
				assert duration < 3000 : duration;
				
				stream.revive();
				
				pipe.getValue1().write(1);
				assertEquals(stream.read(), 1);
				
				asyncFuture.getResult(500); // will throw exception if there's any problem in async thread
			}
		});
	}
	
	/** self-documenting */
	@Test
	public void testKillWithExceptionDuringRead() throws Exception
	{
		TestUtil.runWithTimeLimit(5000, () -> {
			
			Pair<RevivableInputStream, RevivableOutputStream> pipe = TestUtilPipes.createKillableBytePipe(100);
			try (RevivableInputStream stream = pipe.getValue0())
			{
				AsyncTestRunner<Void> asyncFuture = TestUtil.runAsynchronously(() -> {
					Thread.sleep(2000);
					stream.kill(new SocketTimeoutException("intentional SocketTimeoutException"));
				});
				
				long start = System.currentTimeMillis();
				try
				{
					stream.read();
					assert false;
				} catch (SocketTimeoutException e)
				{
					assert e.toString().contains("intentional SocketTimeoutException") : e;
				}
				long duration = System.currentTimeMillis() - start;
				assert duration > 1000 : duration;
				assert duration < 3000 : duration;
				
				stream.revive();
				
				pipe.getValue1().write(1);
				assertEquals(stream.read(), 1);
				
				asyncFuture.getResult(500); // will throw exception if there's any problem in async thread
			}
		});
	}
	
	/** self-documenting */
	@Test()
	public void testExceptionDecoration() throws Exception
	{
		TestUtil.runWithTimeLimit(5000, () -> {
			
			Pair<RevivableInputStream, RevivableOutputStream> pipe = TestUtilPipes.createKillableBytePipe(4);
			try (RevivableInputStream stream = pipe.getValue0())
			{
				stream.setDecorateExceptions(true);
				
				SocketTimeoutException origException = new SocketTimeoutException("intentional SocketTimeoutException");
				stream.queueReadException(origException);
				
				try
				{
					stream.read();
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
			try (RevivableInputStream stream = pipe.getValue0())
			{
				stream.setDecorateExceptions(false);
				
				SocketTimeoutException origException = new SocketTimeoutException("intentional SocketTimeoutException");
				stream.queueReadException(origException);
				
				try
				{
					stream.read();
					assert false;
				} catch (Exception e)
				{
					assertEquals(e, origException);
				}
			}
		});
	}
}
