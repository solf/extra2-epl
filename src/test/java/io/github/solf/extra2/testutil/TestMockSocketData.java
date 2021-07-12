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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.testng.annotations.Test;

import io.github.solf.extra2.testutil.MockSocketData;
import io.github.solf.extra2.testutil.TestUtil;
import io.github.solf.extra2.testutil.TestUtil.AsyncTestRunner;

/**
 * Tests for {@link MockSocketData}
 *
 * @author Sergey Olefir
 */
@NonNullByDefault
public class TestMockSocketData
{
	/** self-documenting */
	@Test
	public void testSocketInputPipe() throws Exception
	{
		TestUtil.runWithTimeLimit(5000, () -> {
			MockSocketData mockSocketData = MockSocketData.createSocket(100);
			
			try (
				InputStream input = mockSocketData.getMockSocket().getInputStream();
				OutputStream output = mockSocketData.getOutputStream())
			{
				for (int i = 0; i < 50; i++)
					output.write(i);
				output.flush();
				
				for (int i = 0; i < 50; i++)
					assertEquals(input.read(), i);
			}
		});
	}
	
	/** self-documenting */
	@Test
	public void testSocketOutputPipe() throws Exception
	{
		TestUtil.runWithTimeLimit(5000, () -> {
			MockSocketData mockSocketData = MockSocketData.createSocket(100);
			
			try (
				InputStream input = mockSocketData.getInputStream();
				OutputStream output = mockSocketData.getMockSocket().getOutputStream())
			{
				for (int i = 0; i < 50; i++)
					output.write(i);
				output.flush();
				
				for (int i = 0; i < 50; i++)
					assertEquals(input.read(), i);
			}
		});
	}
	
	/** self-documenting */
	@Test
	public void testInetAddress() throws Exception
	{
		TestUtil.runWithTimeLimit(5000, () -> {
			MockSocketData mockSocketData = MockSocketData.createSocket(100);
			
			assertEquals(mockSocketData.getMockSocket().getInetAddress(), MockSocketData.MOCK_SOCKET_INET_ADDRESS);
		});
	}
	
	/** self-documenting */
	@Test
	public void testSmallBufferSizeSocketOutputPipe() throws Exception
	{
		TestUtil.runWithTimeLimit(5000, () -> {
			MockSocketData mockSocketData = MockSocketData.createSocket(10);
			
			final AtomicInteger counter = new AtomicInteger(0);
			
			AsyncTestRunner<Void> asyncThread = TestUtil.runAsynchronously(() -> {
				for (int i = 0; i < 50; i++)
				{
					mockSocketData.getMockSocket().getOutputStream().write(i);
					counter.incrementAndGet();
				}
			});
			
			try
			{
				asyncThread.getResult(1000);
				assert false;
			} catch (ExecutionException e)
			{
				assert e.getCause() instanceof java.io.InterruptedIOException : e;
			}
			
			int v = counter.get();
			assert v < 50 : v;
		});
	}
	
	/** self-documenting */
	@Test
	public void testSmallBufferSizeSocketInputPipe() throws Exception
	{
		TestUtil.runWithTimeLimit(5000, () -> {
			MockSocketData mockSocketData = MockSocketData.createSocket(10);
			
			final AtomicInteger counter = new AtomicInteger(0);
			
			AsyncTestRunner<Void> asyncThread = TestUtil.runAsynchronously(() -> {
				for (int i = 0; i < 50; i++)
				{
					mockSocketData.getOutputStream().write(i);
					counter.incrementAndGet();
				}
			});
			
			try
			{
				asyncThread.getResult(1000);
				assert false;
			} catch (ExecutionException e)
			{
				assert e.getCause() instanceof java.io.InterruptedIOException : e;
			}
			
			int v = counter.get();
			assert v < 50 : v;
		});
	}
	
	/** self-documenting */
	@Test
	public void testSocketInputControl() throws Exception
	{
		TestUtil.runWithTimeLimit(5000, () -> {
			MockSocketData mockSocketData = MockSocketData.createSocket(10);
			
			mockSocketData.getControlForSocketInput().kill();
			
			assertEquals(mockSocketData.getMockSocket().getInputStream().read(), -1);
		});
	}
	
	/** self-documenting */
	@Test
	public void testSocketOutputControl() throws Exception
	{
		TestUtil.runWithTimeLimit(5000, () -> {
			MockSocketData mockSocketData = MockSocketData.createSocket(10);
			
			mockSocketData.getControlForSocketOutput().kill();
			
			try
			{
				mockSocketData.getMockSocket().getOutputStream().write(5);
				assert false;
			} catch (IOException e)
			{
				assert e.toString().contains("Stream [temporarily] killed") : e;
			}
		});
	}
}
