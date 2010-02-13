/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * his work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.amqp.protocol.marshaller.v1_0_0;

import java.io.DataInput;
import java.io.IOException;
import org.apache.activemq.amqp.protocol.marshaller.AmqpEncodingError;
import org.apache.activemq.amqp.protocol.marshaller.Encoded;
import org.apache.activemq.amqp.protocol.marshaller.UnexpectedTypeException;
import org.apache.activemq.amqp.protocol.marshaller.v1_0_0.Encoder;
import org.apache.activemq.amqp.protocol.marshaller.v1_0_0.Encoder.*;
import org.apache.activemq.amqp.protocol.types.AmqpFilter;
import org.apache.activemq.amqp.protocol.types.AmqpSymbol;
import org.apache.activemq.amqp.protocol.types.AmqpType;
import org.apache.activemq.amqp.protocol.types.AmqpUlong;
import org.apache.activemq.amqp.protocol.types.IAmqpList;
import org.apache.activemq.util.buffer.Buffer;

public class AmqpFilterMarshaller implements DescribedTypeMarshaller<AmqpFilter>{

    static final AmqpFilterMarshaller SINGLETON = new AmqpFilterMarshaller();
    private static final Encoded<IAmqpList<AmqpType<?, ?>>> NULL_ENCODED = new Encoder.NullEncoded<IAmqpList<AmqpType<?, ?>>>();

    public static final String SYMBOLIC_ID = "amqp:filter:list";
    //Format code: 0x00000001:0x00009703:
    public static final long CATEGORY = 1;
    public static final long DESCRIPTOR_ID = 38659;
    public static final long NUMERIC_ID = CATEGORY << 32 | DESCRIPTOR_ID; //(4295005955L)
    //Hard coded descriptor:
    public static final EncodedBuffer DESCRIPTOR = FormatCategory.createBuffer(new Buffer(new byte [] {
        (byte) 0x80,                                         // ulong descriptor encoding)
        (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x01,  // CATEGORY CODE
        (byte) 0x00, (byte) 0x00, (byte) 0x97, (byte) 0x03   // DESCRIPTOR ID CODE
    }), 0);

    private static final ListDecoder<AmqpType<?, ?>> DECODER = new ListDecoder<AmqpType<?, ?>>() {
        public final IAmqpList<AmqpType<?, ?>> unmarshalType(int dataCount, int dataSize, DataInput in) throws AmqpEncodingError, IOException {
            if (dataCount > 2) {
                throw new AmqpEncodingError("Too many fields for " + SYMBOLIC_ID + ": " + dataCount);
            }
            IAmqpList<AmqpType<?, ?>> rc = new IAmqpList.ArrayBackedList<AmqpType<?, ?>>(new AmqpType<?, ?>[2]);
            //filter-type:
            if(dataCount > 0) {
                rc.set(0, AmqpSymbol.AmqpSymbolBuffer.create(AmqpSymbolMarshaller.createEncoded(in)));
                dataCount--;
            }
            else {
                throw new AmqpEncodingError("Missing required field for " + SYMBOLIC_ID + ": filter-type");
            }

            //filter:
            if(dataCount > 0) {
                rc.set(1, AmqpMarshaller.SINGLETON.unmarshalType(in));
                dataCount--;
            }
            else {
                throw new AmqpEncodingError("Missing required field for " + SYMBOLIC_ID + ": filter");
            }
            return rc;
        }

        public IAmqpList<AmqpType<?, ?>> decode(EncodedBuffer[] constituents) {
            if (constituents.length > 2) {
                throw new AmqpEncodingError("Too many fields for " + SYMBOLIC_ID + ":" + constituents.length);
            }
            int dataCount = constituents.length;
            IAmqpList<AmqpType<?, ?>> rc = new IAmqpList.ArrayBackedList<AmqpType<?, ?>>(new AmqpType<?, ?>[2]);
            //filter-type:
            if(dataCount > 0) {
                rc.set(0, AmqpSymbol.AmqpSymbolBuffer.create(AmqpSymbolMarshaller.createEncoded(constituents[0])));
                dataCount--;
            }
            else {
                throw new AmqpEncodingError("Missing required field for " + SYMBOLIC_ID + ": filter-type");
            }

            //filter:
            if(dataCount > 0) {
                rc.set(1, AmqpMarshaller.SINGLETON.decodeType(constituents[1]));
                dataCount--;
            }
            else {
                throw new AmqpEncodingError("Missing required field for " + SYMBOLIC_ID + ": filter");
            }
            return rc;
        }
    };

    public static class AmqpFilterEncoded extends DescribedEncoded<IAmqpList<AmqpType<?, ?>>> {

        public AmqpFilterEncoded(DescribedBuffer buffer) {
            super(buffer);
        }

        public AmqpFilterEncoded(AmqpFilter value) {
            super(AmqpListMarshaller.encode(value));
        }

        protected final String getSymbolicId() {
            return SYMBOLIC_ID;
        }

        protected final long getNumericId() {
            return NUMERIC_ID;
        }

        protected final Encoded<IAmqpList<AmqpType<?, ?>>> decodeDescribed(EncodedBuffer encoded) throws AmqpEncodingError {
            return AmqpListMarshaller.createEncoded(encoded, DECODER);
        }

        protected final Encoded<IAmqpList<AmqpType<?, ?>>> unmarshalDescribed(DataInput in) throws IOException {
            return AmqpListMarshaller.createEncoded(in, DECODER);
        }

        protected final EncodedBuffer getDescriptor() {
            return DESCRIPTOR;
        }
    }

    public static final Encoded<IAmqpList<AmqpType<?, ?>>> encode(AmqpFilter value) throws AmqpEncodingError {
        return new AmqpFilterEncoded(value);
    }

    static final Encoded<IAmqpList<AmqpType<?, ?>>> createEncoded(Buffer source, int offset) throws AmqpEncodingError {
        return createEncoded(FormatCategory.createBuffer(source, offset));
    }

    static final Encoded<IAmqpList<AmqpType<?, ?>>> createEncoded(DataInput in) throws IOException, AmqpEncodingError {
        return createEncoded(FormatCategory.createBuffer(in.readByte(), in));
    }

    static final Encoded<IAmqpList<AmqpType<?, ?>>> createEncoded(EncodedBuffer buffer) throws AmqpEncodingError {
        byte fc = buffer.getEncodingFormatCode();
        if (fc == Encoder.NULL_FORMAT_CODE) {
            return NULL_ENCODED;
        }

        DescribedBuffer db = buffer.asDescribed();
        AmqpType<?, ?> descriptor = AmqpMarshaller.SINGLETON.decodeType(db.getDescriptorBuffer());
        if(!(descriptor instanceof AmqpUlong && ((AmqpUlong)descriptor).getValue().longValue() == NUMERIC_ID ||
               descriptor instanceof AmqpSymbol && ((AmqpSymbol)descriptor).getValue().equals(SYMBOLIC_ID))) {
            throw new UnexpectedTypeException("descriptor mismatch: " + descriptor);
        }
        return new AmqpFilterEncoded(db);
    }

    public final AmqpFilter.AmqpFilterBuffer decodeDescribedType(AmqpType<?, ?> descriptor, DescribedBuffer encoded) throws AmqpEncodingError {
        return AmqpFilter.AmqpFilterBuffer.create(new AmqpFilterEncoded(encoded));
    }
}