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
package io.github.solf.extra2.testutil.sockets.test;

import java.io.IOException;
import java.net.Socket;

import javax.annotation.NonNullByDefault;

import io.github.solf.extra2.testutil.MockSocketService;
import io.github.solf.extra2.testutil.sockets.ExampleSocketsUsingService;
import lombok.Getter;

/**
 * Used for testing operation of {@link ExampleSocketsUsingService}
 *
 * @author Sergey Olefir
 */
@NonNullByDefault
public class MockExampleSocketsUsingService extends ExampleSocketsUsingService
{
	/**
	 * Mock socket service used for faking sockets for testing.
	 */
	@Getter
	private final MockSocketService mockSocketService = new MockSocketService(10240);
	

	/**
	 * Constructor.
	 */
	public MockExampleSocketsUsingService(String remoteAddr, int remotePort,
		long connectTimeout, long soTimeout)
	{
		super(remoteAddr, remotePort, connectTimeout, soTimeout);
	}


	/*
	 * mock the socket!
	 */
	@Override
	protected Socket connectSocket(String destAddress, int destPort,
		long connectTimeoutTime)
		throws IOException
	{
		return mockSocketService.connectSocket(destAddress, destPort, connectTimeoutTime);
	}
}
