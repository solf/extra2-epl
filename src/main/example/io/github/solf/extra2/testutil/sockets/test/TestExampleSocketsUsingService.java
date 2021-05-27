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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;

import javax.annotation.NonNullByDefault;

import org.testng.annotations.Test;

import io.github.solf.extra2.testutil.MockSocketData;
import io.github.solf.extra2.testutil.TestUtil;
import io.github.solf.extra2.testutil.TestUtil.AsyncTestRunner;
import io.github.solf.extra2.testutil.sockets.ExampleSocketsUsingService;

/**
 * Tests {@link ExampleSocketsUsingService}
 *
 * @author Sergey Olefir
 */
@NonNullByDefault
public class TestExampleSocketsUsingService
{
	@Test
	public void test() throws Exception
	{
		MockExampleSocketsUsingService service = new MockExampleSocketsUsingService("anaddr", 1234, 5000, 5000);
		
		// Run service check asynchronously
		AsyncTestRunner<Void> asyncFuture = TestUtil.runAsynchronously(() -> {
			service.checkRemoteIsAlive();
		});
		
		// Run our checks with time limit to prevent unexpected hang-ups
		TestUtil.runWithTimeLimit(10000, () -> {
		
			MockSocketData mockSocketData = service.getMockSocketService().waitForAndClearTheOnlyConnectedSocketMock(2000);
			
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(mockSocketData.getInputStream()));
				PrintWriter writer = new PrintWriter(mockSocketData.getOutputStream(), true/*auto-flush*/);)
			{
				// Check correct prompt
				String line = reader.readLine();
				assertEquals("PING", line);
				
				writer.println("ACK"); // send correct response
				
				// Check socket is closed.
				assertNull(reader.readLine());
			}
		});
		
		asyncFuture.getResult(1000); // will throw exception if async thread doesn't finish or throws exception 
	}
}
