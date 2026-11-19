package com.fde.workbench.common.bo;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;

/**
 * {@link RevisionStamp} 的序列化：输出毫秒时间戳整数，而不是 {@code {"epochMilli": ...}} 对象。
 * <p>
 * 与 api.md {@code "revision": 2} 的形状一致；前端把它当不透明数字，原样回传即可。
 */
public class RevisionStampIntSerializer extends StdSerializer<RevisionStamp> {

    private static final long serialVersionUID = 1L;

    /** Jackson 需要公开的无参构造 */
    public RevisionStampIntSerializer() {
        super(RevisionStamp.class);
    }

    @Override
    public void serialize(RevisionStamp value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        gen.writeNumber(value.epochMilli());
    }
}
