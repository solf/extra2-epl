/**
 * Input stream that decompresses data. 
 * 
 * Copyright 2005 - Philip Isenhour - http://javatechniques.com/
 * 
 * This software is provided 'as-is', without any express or 
 * implied warranty. In no event will the authors be held liable 
 * for any damages arising from the use of this software.
 *
 * Permission is granted to anyone to use this software for any 
 * purpose, including commercial applications, and to alter it and 
 * redistribute it freely, subject to the following restrictions:
 *
 *  1. The origin of this software must not be misrepresented; you 
 *     must not claim that you wrote the original software. If you 
 *     use this software in a product, an acknowledgment in the 
 *     product documentation would be appreciated but is not required.
 *
 *  2. Altered source versions must be plainly marked as such, and 
 *     must not be misrepresented as being the original software.
 *
 *  3. This notice may not be removed or altered from any source 
 *     distribution.
 *     
 * Sergey Olefir: this code has been modified -- mostly cleaning up warnings,
 * adding required annotations and cleaning up documentation.
 *
 * $Id:  1.2 2005/10/26 17:40:19 isenhour Exp $
 */
package io.github.solf.extra2.io;

import static io.github.solf.extra2.util.NullUtil.fakeNonNull;
import static io.github.solf.extra2.util.NullUtil.nullable;

import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterInputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

import javax.annotation.Nullable;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Input stream that decompresses data.
 * 
 * Must be used together with {@link CompressedBlockOutputStream}.
 * 
 * Better than standard Java compression implementations: 
 * - doesn't block on constructor unlike {@link GZIPInputStream} 
 * - doesn't have problems reading (stuck waiting for data) unlike 
 * 		{@link DeflaterInputStream} when wrapped into {@link InputStreamReader} 
 * 		-- underneath this uses internal sun class and I don't have source on 
 * 		hand, so not sure why this was getting stuck even when client flushed 
 * 		the data (Sergey Olefir)
 * 
 * The underlying compression uses {@link Deflater} / {@link Inflater}.
 * 
 * NOT THREAD-SAFE.
 */
@NonNullByDefault
public class CompressedBlockInputStream extends FilterInputStream
{
	/**
	 * Maximum allowed internal buffers size (there are two of them) -- used
	 * to avoid trying to allocate absurd memory blocks due to errors or 
	 * maliciously crafted headers. 
	 */
	protected final long maximumBufferSize;
	
	/**
	 * Buffer of compressed data read from the stream
	 */
	private byte[] inBuf = fakeNonNull();

	/**
	 * Length of data in the input data
	 */
	private int inLength = 0;

	/**
	 * Buffer of uncompressed data
	 */
	private byte[] outBuf = fakeNonNull();

	/**
	 * Offset and length of uncompressed data
	 */
	private int outOffs = 0;
	private int outLength = 0;

	/**
	 * Inflater for decompressing
	 */
	private final Inflater inflater;
	
	/**
	 * Blocking IOException -- if not null, it is thrown in response to any
	 * method calls.
	 */
	@Nullable
	private IOException blockingException;
	
	/**
	 * Whether EOF was reached in {@link #readAndDecompress()}
	 */
	private boolean eofReached = false;

	/**
	 * This constructor defaults to 1MB maximum internal buffer size.
	 * 
	 * @see #CompressedBlockInputStream(InputStream, long)
	 */
	public CompressedBlockInputStream(InputStream is)
	{
		this(is, 1024 * 1024); // Default to 1MB
	}
	
	/**
	 * Constructor.
	 * 
	 * @param maximumBufferSize maximum allowed internal buffers size (there are 
	 * 		two of them) -- used to avoid trying to allocate absurd memory blocks 
	 * 		due to errors or maliciously crafted headers -- this should be in-sync
	 * 		with buffers size set in {@link CompressedBlockOutputStream} 
	 */
	public CompressedBlockInputStream(InputStream is, long maximumBufferSize)
	{
		super(is);
		
		this.inflater = new Inflater();
		this.maximumBufferSize = maximumBufferSize;
	}

	protected void readAndDecompress()
		throws IOException
	{
		if (eofReached)
			throw new EOFException();
		
		try
		{
			readAndDecompress1();
		} catch (EOFException e)
		{
			eofReached = true;
			throw e;
		} catch (IOException e)
		{
			blockingException = e;
			throw e;
		}
	}
	
	protected void readAndDecompress1()
		throws IOException
	{
		// Check that magic bytes are okay.
		for (byte expected : CompressedBlockOutputStream.MAGIC_NUMBER)
		{
			int val = in.read();
			if (val < 0)
				throw new EOFException(); 
			byte actual = (byte)val;
			if (expected != actual)
				throw new ZipException("Wrong magic number -- incoming data is not compressed by CompressedBlockOutputStream?");
		}
		
		// Read the length of the compressed block
		int ch1 = in.read();
		int ch2 = in.read();
		int ch3 = in.read();
		int ch4 = in.read();
		if( (ch1 | ch2 | ch3 | ch4) < 0 )
			throw new EOFException();
		inLength = ((ch1 << 24) + (ch2 << 16) + (ch3 << 8) + (ch4 << 0));

		ch1 = in.read();
		ch2 = in.read();
		ch3 = in.read();
		ch4 = in.read();
		if( (ch1 | ch2 | ch3 | ch4) < 0 )
			throw new EOFException();
		outLength = ((ch1 << 24) + (ch2 << 16) + (ch3 << 8) + (ch4 << 0));

		// Make sure we've got enough space to read the block
		if( (nullable(inBuf) == null) || (inLength > inBuf.length) )
		{
			if (inLength > maximumBufferSize)
				throw new ZipException("Requested compressed data buffer size exceeds maximum buffer size: " + inLength + "/" + maximumBufferSize);
			inBuf = new byte[inLength];
		}

		if( (nullable(outBuf) == null) || (outLength > outBuf.length) )
		{
			if (outLength > maximumBufferSize)
				throw new ZipException("Requested decompressed data buffer size exceeds maximum buffer size: " + inLength + "/" + maximumBufferSize);
			outBuf = new byte[outLength];
		}

		// Read until we're got the entire compressed buffer.
		// read(...) will not necessarily block until all
		// requested data has been read, so we loop until
		// we're done.
		int inOffs = 0;
		while( inOffs < inLength )
		{
			int inCount = in.read(inBuf, inOffs, inLength - inOffs);
			if( inCount == -1 )
			{
				throw new EOFException();
			}
			inOffs += inCount;
		}

		inflater.setInput(inBuf, 0, inLength);
		try
		{
			inflater.inflate(outBuf);
		} catch( DataFormatException dfe )
		{
			throw new IOException(
				"Data format exception - " + dfe.getMessage());
		}

		// Reset the inflator so we can re-use it for the
		// next block
		inflater.reset();

		outOffs = 0;
	}

	@Override
	public int read()
		throws IOException
	{
		checkIfBlocked();
		
		if( outOffs >= outLength )
		{
			try
			{
				readAndDecompress();
			} catch( EOFException eof )
			{
				return -1;
			}
		}

		return outBuf[outOffs++] & 0xff;
	}

	@Override
	@NonNullByDefault({}) // because cannot externally annotate proper class properly -- bug in Eclipse?
	public int read(byte[] b, int off, int len)
		throws IOException
	{
		checkIfBlocked();
		
		int count = 0;
		while( count < len )
		{
			if( outOffs >= outLength )
			{
				try
				{
					// If we've read at least one decompressed
					// byte and further decompression would
					// require blocking, return the count.
					if( (count > 0) && (in.available() == 0) )
						return count;
					else
						readAndDecompress();
				} catch( EOFException eof )
				{
					if( count == 0 )
						count = -1;
					return count;
				}
			}

			int toCopy = Math.min(outLength - outOffs, len - count);
			System.arraycopy(outBuf, outOffs, b, off + count, toCopy);
			outOffs += toCopy;
			count += toCopy;
		}

		return count;
	}

	@Override
	public int available()
		throws IOException
	{
		checkIfBlocked();
		
		// This isn't precise, but should be an adequate
		// lower bound on the actual amount of available data
		return (outLength - outOffs) + in.available();
	}

	/**
	 * Check if method invocation needs to be blocked -- if so, throws an exception.
	 */
	protected void checkIfBlocked() throws IOException
	{
		@Nullable IOException e = blockingException;
		if (e != null)
			throw new IOException("Stream is unavailable because reading already failed previously: " + e, e);
	}
}