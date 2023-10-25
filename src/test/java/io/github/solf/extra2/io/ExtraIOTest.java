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
package io.github.solf.extra2.io;

import static io.github.solf.extra2.util.NullUtil.nnChecked;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintWriter;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.testng.annotations.Test;

/**
 * Tests for io.github.solf.extra2.io
 *
 * @author Sergey Olefir
 */
@NonNullByDefault
public class ExtraIOTest
{
	/**
	 * End-of-File (or whatever) marker.
	 */
	private static final String EOF_MARKER = "<EOF>";

	/**
	 * End-of-File (or whatever) marker for byte streams
	 */
	private static final byte BYTE_EOF_MARKER = Byte.MIN_VALUE;

	/**
	 * Exception marker for byte streams
	 */
	private static final byte BYTE_EXCEPTION_MARKER = BYTE_EOF_MARKER + 1;
	
	/**
	 * Tests for {@link CompressedBlockOutputStream} & {@link CompressedBlockInputStream}
	 */
	@SuppressWarnings("resource")
	@Test
	public void testCompressedBlockStreams() throws IOException, InterruptedException
	{
		{
			// Test that flushing and closing stream works as expected.
			PipedInputStream pis = new PipedInputStream();
			CompressedBlockInputStream cbis = new CompressedBlockInputStream(pis);
			BufferedReader in = new BufferedReader(new InputStreamReader(
				cbis));
			PipedOutputStream pos = new PipedOutputStream(pis);
			PrintWriter out = new PrintWriter(new OutputStreamWriter(
				new CompressedBlockOutputStream(pos, 16384)), true); // auto-flush on
			SynchronousQueue<String> pipe = createAsyncReader(in, "testCompressedBlockStreams1 reader");
			
			assert pipe.poll(1, TimeUnit.SECONDS) == null;
			
			out.println("line1");
			assert "line1".equals(pipe.poll(1, TimeUnit.SECONDS));
			out.println("line2");
			assert "line2".equals(pipe.poll(1, TimeUnit.SECONDS));
			
			out.print("finalLine"); // not flushed!
			assert pipe.poll(1, TimeUnit.SECONDS) == null;
			
			out.close(); // should flush and close
			assert "finalLine".equals(pipe.poll(1, TimeUnit.SECONDS));
			
			assert EOF_MARKER == pipe.poll(1, TimeUnit.SECONDS);
			
			// check what happens if reading past EOF again
			assert -1 == cbis.read();
			assert -1 == cbis.read(new byte[10]);
		}
		
		{
			// Test that buffers are flushed when they exceed some size.
			PipedInputStream pis = new PipedInputStream();
			CompressedBlockInputStream in = new CompressedBlockInputStream(pis);
			PipedOutputStream pos = new PipedOutputStream(pis);
			CompressedBlockOutputStream out = new CompressedBlockOutputStream(pos, 16); // very small buffer
			SynchronousQueue<Byte> pipe = createAsyncReader(in, "testCompressedBlockStreams1 reader");
			
			out.write("line1".getBytes());
			assert pipe.poll(1, TimeUnit.SECONDS) == null; // expect data to be still buffered
			out.write("0123456789a123456789".getBytes()); // This should exceed buffer size and flush
			for (byte b : "line1".getBytes())
				assert b == pipe.poll(1, TimeUnit.SECONDS);
			
			out.close(); // should flush and close
			for (byte b : "0123456789a123456789".getBytes())
				assert b == pipe.poll(1, TimeUnit.SECONDS);
			
			assert BYTE_EOF_MARKER == pipe.poll(1, TimeUnit.SECONDS);
		}
		
		{
			// Test that limit on size in CompressedBlockInputStream actually works.
			PipedInputStream pis = new PipedInputStream();
			BufferedReader in = new BufferedReader(new InputStreamReader(
				new CompressedBlockInputStream(pis, 32))); // 32 bytes buffer in input stream -- with header information enough to send a few bytes
			PipedOutputStream pos = new PipedOutputStream(pis);
			PrintWriter out = new PrintWriter(new OutputStreamWriter(
				new CompressedBlockOutputStream(pos, 16384)), true); // auto-flush on
			SynchronousQueue<String> pipe = createAsyncReader(in, "testCompressedBlockStreams1 reader");
			
			
			assert pipe.poll(1, TimeUnit.SECONDS) == null;
			
			out.println("line1");
			assert "line1".equals(pipe.poll(1, TimeUnit.SECONDS));
			
			out.println("long line - 0123456789a123456789b123456789c123456789d123456789e123456789");
//			System.out.println(pipe.poll(1, TimeUnit.SECONDS));
			assert nnChecked(pipe.poll(1, TimeUnit.SECONDS)).startsWith("Exception: java.util.zip.ZipException:");
			
			// Attempt further reading which should fail.
			try
			{
				System.err.println(in.readLine());
				assert false;
			} catch (IOException e)
			{
				assert e.toString().contains("Stream is unavailable") : e;
				assert e.toString().contains("java.util.zip.ZipException") : e;
			}
		}
	}

	/**
	 * Creates and runs a thread for async-reading given reader line-by-line
	 * and puts result in the returned queue.
	 * At the end puts {@link #EOF_MARKER} (after reading null)
	 */
	private SynchronousQueue<String> createAsyncReader(BufferedReader in, String threadName)
	{
		SynchronousQueue<String> pipe = new SynchronousQueue<>();
		new Thread(() -> { // Run reading asynchronously to avoid 'wait forever' problem in case of error.
			try
			{
				try
				{
					while(true)
					{
						@Nullable String line = in.readLine();
						if (line == null)
						{
							pipe.put(EOF_MARKER);
							break;
						}
						pipe.put(line);
					}
				} catch (IOException e)
				{
					e.printStackTrace();
					pipe.put("Exception: " + e);
				}
			} catch (InterruptedException e)
			{
				// whatever
			}
		}, threadName).start();
		return pipe;
	}

	/**
	 * Creates and runs a thread for async-reading given input stream byte-by-byte
	 * and puts result in the returned queue.
	 * At the end puts {@link #EOF_MARKER} (after reading null)
	 */
	private SynchronousQueue<Byte> createAsyncReader(InputStream in, String threadName)
	{
		SynchronousQueue<Byte> pipe = new SynchronousQueue<>();
		new Thread(() -> { // Run reading asynchronously to avoid 'wait forever' problem in case of error.
			try
			{
				try
				{
					while(true)
					{
						int v = in.read();
						if (v == -1)
						{
							pipe.put(BYTE_EOF_MARKER);
							break;
						}
						pipe.put((byte)v);
					}
				} catch (IOException e)
				{
					e.printStackTrace();
					pipe.put(BYTE_EXCEPTION_MARKER);
				}
			} catch (InterruptedException e)
			{
				// whatever
			}
		}, threadName).start();
		return pipe;
	}
}
