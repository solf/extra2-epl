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
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.javatuples.Pair;

/**
 * Utilities for writing tests dealing with pipes (e.g. streams, sockets).
 *
 * @author Sergey Olefir
 */
@NonNullByDefault
public class TestUtilPipes
{
	/**
	 * Creates byte pipe (InputStream, OutputStream) that is very useful for 
	 * sending/receiving data from methods that deal with streams.
	 * <p>
	 * This pipe is guaranteed to react to Thread.interrupt() unlike blocking
	 * reads in some other cases in Java.
	 * <p>
	 * Additionally {@link RevivableInputStream} may be used to (temporarily)
	 * signal 'end of file' to the reader by using {@link RevivableInputStream#kill()}
	 * 
	 * @param bufferSize buffer size for internal buffers -- note that there are
	 * 		at least two different buffers + data that is in-flight, so the
	 * 		actual size of data that can be 'in the pipes' might be roughly
	 * 		3 times as much as this size
	 */
	@SuppressWarnings("resource")
	public static Pair<RevivableInputStream, RevivableOutputStream> createKillableBytePipe(int bufferSize) throws IOException
	{
		PipedOutputStream pos = new PipedOutputStream();
		PipedInputStream pis = new PipedInputStream(pos, bufferSize);
		RevivableInputStream is = new RevivableInputStream(pis);
		RevivableOutputStream os = new RevivableOutputStream(pos, bufferSize);
		
		return new Pair<RevivableInputStream, RevivableOutputStream>(is, os);
	}
	
	/**
	 * Factory for creating mock sockets.
	 * 
	 * @param bufferSize buffer size for internal buffers -- note that there are
	 * 		at least two different buffers + data that is in-flight, so the
	 * 		actual size of data that can be 'in the pipes' might be roughly
	 * 		3 times as much as this size
	 */
	public static MockSocketData createMockSocket(int bufferSize) throws IOException
	{
		return MockSocketData.createSocket(bufferSize);
	}
}
