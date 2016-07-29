/*
 * The MIT License
 *
 * Copyright 2016 Ahseya.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.github.horrorho.inflatabledonkey.chunk.engine;

import com.github.horrorho.inflatabledonkey.chunk.Chunk;
import com.github.horrorho.inflatabledonkey.chunk.store.ChunkStore;
import com.github.horrorho.inflatabledonkey.io.IOFunction;
import com.github.horrorho.inflatabledonkey.protobuf.ChunkServer.ChunkInfo;
import com.github.horrorho.inflatabledonkey.protobuf.ChunkServer.ChunkReference;
import com.github.horrorho.inflatabledonkey.protobuf.ChunkServer.StorageHostChunkList;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.jcip.annotations.ThreadSafe;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.BoundedInputStream;
import org.apache.commons.io.output.NullOutputStream;
import org.bouncycastle.crypto.engines.AESFastEngine;
import org.bouncycastle.crypto.modes.CFBBlockCipher;
import org.bouncycastle.crypto.io.CipherInputStream;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.util.encoders.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decrypts storage host chunk list streams. Limited to type 0x01 chunk decryption.
 *
 * @author Ahseya
 */
@ThreadSafe
public final class ChunkListDecrypter implements IOFunction<InputStream, Map<ChunkReference, Chunk>> {

    private static final Logger logger = LoggerFactory.getLogger(ChunkListDecrypter.class);

    private final ChunkStore store;
    private final StorageHostChunkList container;
    private final int containerIndex;

    public ChunkListDecrypter(ChunkStore store, StorageHostChunkList container, int containerIndex) {
        this.store = Objects.requireNonNull(store);
        this.container = Objects.requireNonNull(container);
        this.containerIndex = containerIndex;
    }

    @Override
    public Map<ChunkReference, Chunk> apply(InputStream inputStream) throws IOException {
        // ChunkInfos can reference the same chunk block offset, but with different wrapped 0x02 keys. These
        // keys should unwrap to the same 0x01 key with the block yielding the same output data.
        // We assume identical offsets yield identical data which allow us simpler data streaming.
        try {
            logger.trace("<< apply() - InputStream: {}", inputStream);

            Map<ChunkReference, Chunk> chunks = new HashMap<>();
            List<ChunkInfo> list = container.getChunkInfoList();
            for (int i = 0, offset = 0, n = list.size(); i < n; i++) {
                ChunkInfo chunkInfo = list.get(i);
                int chunkOffset = chunkInfo.getChunkOffset();
                if (chunkOffset > offset) {
                    logger.warn("-- apply() - bad offset");
                    break;
                } else if (chunkOffset != offset) {
                    logger.debug("-- apply() - duplicate offset chunkInfo: {}", chunkInfo);
                    continue;
                }
                offset += chunkInfo.getChunkLength();
                ChunkReference chunkReference = ChunkReferences.chunkReference(containerIndex, i);
                chunk(inputStream, chunkInfo).ifPresent(u -> chunks.put(chunkReference, u));
            }

            logger.trace(">> apply() - chunks: {}", chunks);
            return chunks;
        } finally {
            IOUtils.closeQuietly(inputStream);
        }
    }

    Optional<Chunk> chunk(InputStream inputStream, ChunkInfo chunkInfo) throws IOException {
        logger.trace("<< chunk() - chunkInfo: {} index: {}", chunkInfo);

        BoundedInputStream bis = new BoundedInputStream(inputStream, chunkInfo.getChunkLength());
        bis.setPropagateClose(false);
        Optional<Chunk> chunk = chunk(bis, chunkInfo);
        consume(bis);

        logger.trace(">> chunk() - chunk: {}", chunk);
        return chunk;
    }

    Optional<Chunk> chunk(BoundedInputStream bis, ChunkInfo chunkInfo) throws IOException {
        byte[] checksum = chunkInfo.getChunkChecksum().toByteArray();
        Optional<Chunk> chunk = store.chunk(checksum);
        if (chunk.isPresent()) {
            logger.debug("-- chunk() - chunk present in store: 0x{}", Hex.toHexString(checksum));
            return chunk;
        }
        logger.debug("-- chunk() - chunk not present in store: 0x{}", Hex.toHexString(checksum));
        byte[] chunkEncryptionKey = chunkInfo.getChunkEncryptionKey().toByteArray();
        return decrypt(bis, chunkEncryptionKey, checksum);
    }

    Optional<Chunk> decrypt(BoundedInputStream bis, byte[] chunkEncryptionKey, byte[] checksum) throws IOException {
        if (chunkEncryptionKey.length != 0x11 || chunkEncryptionKey[0] != 0x01) {
            logger.warn("-- decrypt() - unsupported chunk encryption key: 0x{}", Hex.toHexString(chunkEncryptionKey));
        } else {
            byte[] key = Arrays.copyOfRange(chunkEncryptionKey, 1, chunkEncryptionKey.length);
            CFBBlockCipher cipher = new CFBBlockCipher(new AESFastEngine(), 128);
            cipher.init(false, new KeyParameter(key));
            store(new CipherInputStream(bis, cipher), checksum);
        }
        return store.chunk(checksum);
    }

    void store(CipherInputStream is, byte[] checksum) throws IOException {
        Optional<OutputStream> os = store.outputStream(checksum);
        if (os.isPresent()) {
            logger.debug("-- store() - copying chunk into store: 0x{}", Hex.toHexString(checksum));
            copy(is, os.get());
        } else {
            logger.debug("-- store() - store now already contains chunk: 0x{}", Hex.toHexString(checksum));
        }
    }

    void consume(InputStream is) throws IOException {
        copy(is, new NullOutputStream());
    }

    void copy(InputStream is, OutputStream os) throws IOException {
        try {
            IOUtils.copy(is, os);
        } finally {
            // ChunkStore errors are lost.
            IOUtils.closeQuietly(is);
            IOUtils.closeQuietly(os);
        }
    }
}
