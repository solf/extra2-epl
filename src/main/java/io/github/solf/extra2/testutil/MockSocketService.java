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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.NoSuchElementException;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;

import io.github.solf.extra2.concurrent.exception.WAInterruptedException;
import io.github.solf.extra2.concurrent.exception.WATimeoutException;
import io.github.solf.extra2.exception.AssertionException;
import lombok.Getter;

/**
 * Service for creating/managing mock sockets for testing -- can be used to
 * test classes that need Socket services -- often by e.g. delegation:
 * <p>
 * @Getter
 * @Delegate
 * private final MockSocketService mockSocketService = new MockSocketService();
 *
 * @author Sergey Olefir
 */
@NonNullByDefault
public class MockSocketService
{
	/**
	 * 'Warms up' services -- particularly mock framework -- this should make
	 * timing execution of socket mock stuff much more reliable.
	 */
	public static void warmUp()
	{
		MockSocketData.warmUp();
	}
	
	/**
	 * Buffer size for the pipes used for socket input/output streams -- i.e. 
	 * how much data can be stored in those pipes before reading
	 */
	private final int bufferSize;
	
	/**
	 * All sockets that were connected via {@link #connectSocket(String, int, long)}
	 * <p>
	 * This is a LIVE view!
	 * 
	 * @see #getAllConnectedSocketMocksClone()
	 */
	@Getter
	protected final LinkedBlockingDeque<MockSocketData> allConnectedSocketMocks = new LinkedBlockingDeque<>();
	
	/**
	 * Constructor.
	 * 
	 * @param bufferSize buffer size for the pipes used for socket input/output
	 * 		streams -- i.e. how much data can be stored in those pipes before reading
	 */
	public MockSocketService(int bufferSize)
	{
		this.bufferSize = bufferSize;
	}
	
	/**
	 * Factory method to create and connect sockets -- so it can be e.g. overridden in tests.
	 * <p>
	 * This uses default timeout settings -- which is the same as calling
	 * {@link #connectSocket(String, int, long)} with zero (0) timeout.
	 * 
	 * @throws IOException this probably is not thrown, but has to be declared
	 * 		for compatibility with 'real' sockets 
	 */
	public Socket connectSocket(String destAddress, int destPort) throws IOException
	{
		return connectSocket(destAddress, destPort, 0);
	}
	
	/**
	 * Factory method to create and connect sockets -- so it can be e.g. overridden in tests.
	 * 
	 * @throws IOException this probably is not thrown, but has to be declared
	 * 		for compatibility with 'real' sockets 
	 */
	public Socket connectSocket(String destAddress, int destPort, long connectTimeoutTime) throws IOException
	{
		MockSocketData mockSocketData = TestUtilPipes.createMockSocket(bufferSize);
		Socket mockSocket = mockSocketData.getMockSocket();
		mockSocket.connect(new InetSocketAddress(destAddress, destPort), (int)connectTimeoutTime);
		
		getAllConnectedSocketMocks().add(mockSocketData);
		
		return mockSocket;
	}

	/**
	 * Gets last connected socket mock.
	 * 
	 * @throws NoSuchElementException if none are connected
	 */
	public MockSocketData getLastConnectedSocketMock() throws NoSuchElementException
	{
		return getAllConnectedSocketMocks().getLast();
	}
	
	/**
	 * Gets the only connected socket mock.
	 * 
	 * @throws NoSuchElementException if none are connected
	 * @throws IllegalStateException if more than one is connected
	 */
	public MockSocketData getTheOnlyConnectedSocketMock() throws NoSuchElementException, IllegalStateException
	{
		LinkedBlockingDeque<MockSocketData> clone = getAllConnectedSocketMocksClone();
		if (clone.size() > 1)
			throw new IllegalStateException("[" + clone.size() + "] connected mock sockets instead of exactly one.");
		
		return clone.getFirst();
	}
	
	/**
	 * Gets the only connected socket mock.
	 * <p>
	 * Fails if there are none or if there are some after one is removed (i.e.
	 * more than one overall).
	 * 
	 * @throws NoSuchElementException if none are connected
	 * @throws IllegalStateException if more than one is connected
	 */
	public MockSocketData getAndClearTheOnlyConnectedSocketMock() throws NoSuchElementException, IllegalStateException
	{
		MockSocketData result = getAllConnectedSocketMocks().pollFirst();
		if (result == null)
			throw new NoSuchElementException("No connected socket mocks available.");

		int size = 1 + getAllConnectedSocketMocks().size();
		if (size > 1)
			throw new IllegalStateException("[" + size + "] connected mock sockets instead of exactly one.");
		
		return result;
	}
	
	/**
	 * Gets the only connected socket mock (if there's one) or waits for the
	 * next one up to the time limit.
	 * <p>
	 * Throws {@link WATimeoutException} if none are connected within time limit 
	 * 
	 * @throws IllegalStateException if more than one is already connected
	 * @throws WAInterruptedException if interrupted during waiting
	 * @throws WATimeoutException if timed out while waiting
	 */
	public MockSocketData waitForAndClearTheOnlyConnectedSocketMock(long timeLimit) 
		throws IllegalStateException, WAInterruptedException, WATimeoutException
	{
		int size = getAllConnectedSocketMocks().size();
		if (size > 1)
			throw new IllegalStateException("[" + size + "] connected mock sockets instead of one or none (yet).");
		
		MockSocketData result;
		try
		{
			result = getAllConnectedSocketMocks().poll(timeLimit, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e)
		{
			throw new WAInterruptedException(e);
		}
		
		if (result == null)
			throw new WATimeoutException("No socket connected in " + timeLimit + "ms");
		
		return result;
	}
	
	/**
	 * Gets and clears all recorded connected mocks (the returned result is
	 * decoupled from the 'live' queue, so further updates will not propagate
	 * to the returned result).
	 * <p>
	 * Internal queue is cleared during this process.
	 */
	public LinkedBlockingDeque<MockSocketData> getAndClearAllConnectedSocketMocks()
	{
		LinkedBlockingDeque<MockSocketData> src = getAllConnectedSocketMocks();
		
		// must not set capacity in order to be able accommodate whatever might be added
		LinkedBlockingDeque<MockSocketData> result = new LinkedBlockingDeque<>();  
		
		while(true)
		{
			MockSocketData next = src.poll();
			if (next == null)
				break;
			
			result.add(next);
		}
		
		return result;
	}
	
	/**
	 * Returns a clone of {@link #getAllConnectedSocketMocks()} to avoid any
	 * concurrent changes.
	 */
	public LinkedBlockingDeque<MockSocketData> getAllConnectedSocketMocksClone()
	{
		LinkedBlockingDeque<MockSocketData> clone = new LinkedBlockingDeque<>(getAllConnectedSocketMocks());
		
		return clone;
	}
	
	/**
	 * Asserts that there are no connected socket mocks.
	 */
	public void assertNoConnectedSocketMocks() throws AssertionException
	{
		int size = getAllConnectedSocketMocks().size();
		if (size > 0)
			throw new AssertionException("There are " + size + " connected socket mocks instead of none.");
	}
}
