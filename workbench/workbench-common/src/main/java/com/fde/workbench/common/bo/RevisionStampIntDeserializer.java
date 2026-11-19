package com.fde.workbench.common.bo;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;

import java.io.IOException;

/**
 * {@link RevisionStamp} 的反序列化：只接受 JSON 整数（毫秒时间戳）。
 * <p>
 * 单独成类而不是内部类：Jackson 反序列化器在运行期会反射调用默认构造器，
 * 做成 package-private 的静态内部类容易被模块可见性影响；顶层 {code public} 类最稳妥。
 * 不接受字符串，是为了让"前端把 revision 编码错了"立刻变成 400，而不是悄悄按 0 处理。
 */
public class RevisionStampIntDeserializer extends StdScalarDeserializer<RevisionStamp> {

    private static final long serialVersionUID = 1L;

    /** Jackson 需要公开的无参构造 */
    public RevisionStampIntDeserializer() {
        super(RevisionStamp.class);
    }

    @Override
    public RevisionStamp deserialize(JsonParser p, DeserializationContext context) throws IOException {
        if (!p.hasToken(JsonToken.VALUE_NUMBER_INT)) {
            return (RevisionStamp) context.handleUnexpectedToken(RevisionStamp.class, p);
        }
        return RevisionStamp.ofEpochMilli(p.getLongValue());
    }
}
