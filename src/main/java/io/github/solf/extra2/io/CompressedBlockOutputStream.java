/**
 * Output stream that compresses data. A compressed block 
 * is generated and transmitted once a given number of bytes 
 * have been written, or when the flush method is invoked.
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
 * $Id:  1.1 2005/10/26 17:19:05 isenhour Exp $
 */
package io.github.solf.extra2.io;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

import javax.annotation.NonNullByDefault;

/**
 * Output stream that compresses data. A compressed block is generated and
 * transmitted once a given number of bytes have been written, or when the flush
 * method is invoked.
 * 
 * Must be used with {@link CompressedBlockInputStream}
 * 
 * Unlike standard Java compression implementations (e.g.
 * {@link DeflaterOutputStream}) properly reacts to {@link #flush()} by default
 * -- by actually compressing & sending all the data so far.
 * 
 * The underlying compression uses {@link Deflater} / {@link Inflater}.
 * 
 * NOT THREAD-SAFE.
 */
@NonNullByDefault
public class CompressedBlockOutputStream extends FilterOutputStream
{
	/**
	 * Magic number (header).
	 * For reference, GZIP uses 0x1F 0X8B
	 */
	public static final byte[] MAGIC_NUMBER = new byte[] {0x1F, (byte)0x8F};
	
	/**
	 * Buffer for input data
	 */
	private final byte[] inBuf;

	/**
	 * Buffer for compressed data to be written
	 */
	private final byte[] outBuf;

	/**
	 * Number of bytes in the buffer
	 */
	private int len = 0;

	/**
	 * Deflater for compressing data
	 */
	private final Deflater deflater;

	/**
	 * Constructs a CompressedBlockOutputStream that writes to the given
	 * underlying output stream 'os' and sends a compressed block once 'size'
	 * byte have been written. The default compression strategy and level are
	 * used.
	 */
	public CompressedBlockOutputStream(OutputStream os, int size)
	{
		this(os, size, Deflater.DEFAULT_COMPRESSION, Deflater.DEFAULT_STRATEGY);
	}

	/**
	 * Constructs a CompressedBlockOutputStream that writes to the given
	 * underlying output stream 'os' and sends a compressed block once 'size'
	 * byte have been written. The compression level should be specified using
	 * the constants defined in java.util.zip.Deflator.
	 */
	public CompressedBlockOutputStream(OutputStream os, int size, int level)
	{
		this(os, size, level, Deflater.DEFAULT_STRATEGY);
	}

	/**
	 * Constructs a CompressedBlockOutputStream that writes to the given
	 * underlying output stream 'os' and sends a compressed block once 'size'
	 * byte have been written. The compression level and strategy should be
	 * specified using the constants defined in java.util.zip.Deflator.
	 */
	public CompressedBlockOutputStream(OutputStream os, int size, int level,
		int strategy)
	{
		super(os);
		this.inBuf = new byte[size];
		this.outBuf = new byte[size + 64];
		this.deflater = new Deflater(level);
		this.deflater.setStrategy(strategy);
	}

	protected void compressAndSend()
		throws IOException
	{
		if( len > 0 )
		{
			deflater.setInput(inBuf, 0, len);
			deflater.finish();
			int size = deflater.deflate(outBuf);

			out.write(MAGIC_NUMBER);
			
			// Write the size of the compressed data, followed
			// by the size of the uncompressed data
			out.write((size >> 24) & 0xFF);
			out.write((size >> 16) & 0xFF);
			out.write((size >> 8) & 0xFF);
			out.write((size >> 0) & 0xFF);

			out.write((len >> 24) & 0xFF);
			out.write((len >> 16) & 0xFF);
			out.write((len >> 8) & 0xFF);
			out.write((len >> 0) & 0xFF);

			out.write(outBuf, 0, size);
			out.flush();

			len = 0;
			deflater.reset();
		}
	}

	@Override
	public void write(int b)
		throws IOException
	{
		inBuf[len++] = (byte)b;
		if( len == inBuf.length )
		{
			compressAndSend();
		}
	}

	@Override
	@NonNullByDefault({}) // because cannot externally annotate proper class properly -- bug in Eclipse?
	public void write(byte[] b, int boff, int blen)
		throws IOException
	{
		while( (len + blen) > inBuf.length )
		{
			int toCopy = inBuf.length - len;
			System.arraycopy(b, boff, inBuf, len, toCopy);
			len += toCopy;
			compressAndSend();
			boff += toCopy;
			blen -= toCopy;
		}
		System.arraycopy(b, boff, inBuf, len, blen);
		len += blen;
	}

	@Override
	public void flush()
		throws IOException
	{
		compressAndSend();
		out.flush();
	}

	@Override
	public void close()
		throws IOException
	{
		compressAndSend();
		out.close();
	}
}