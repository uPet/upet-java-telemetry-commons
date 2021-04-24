package co.upet.commons.async;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

import static co.upet.commons.async.JsonUtils.getText;
import static org.assertj.core.api.Assertions.assertThat;

public class JsonUtilsTest {

    private JsonNode node;

    @Before
    public void initValues() {
        final ObjectMapper objectMapper = new ObjectMapper();
        final DummyObject dummyObject = new DummyObject();
        dummyObject.setField0("value0");
        dummyObject.setField1(42);
        dummyObject.setField2(1000);
        dummyObject.setMap0(Map.of("key0", "value0"));
        node = objectMapper.valueToTree(dummyObject);
    }

    @Test
    public void shouldGetText() {
        assertThat(fieldVal( "field0")).isEqualTo("value0");
    }

    @Test
    public void shouldGetNullText() {
        assertThat(fieldVal( "field0null")).isNull();
        assertThat(fieldVal( "field1null")).isNull();
    }

    @Test
    public void shouldReturnNullForUnknownField() {
        assertThat(fieldVal( "other")).isNull();
    }

    @Test
    public void shouldReturnNumberAsText() {
        assertThat(fieldVal("field1")).isEqualTo("42");
        assertThat(fieldVal("field2")).isEqualTo("1000");
    }

    @Test
    public void shouldReturnNullForObjectField() {
        assertThat(fieldVal("map0")).isNull();
    }

    @Test
    public void shouldReturnNullForNullNode() {
        assertThat(getText(null,"field1")).isNull();
    }

    private String fieldVal(String name) {
        return getText(node, name);
    }

    @Data
    private static class DummyObject {
        private String field0;
        private String field0null;
        private Integer field1;
        private Integer field1null;
        private long field2;
        private long field2null;
        private Map<String, Object> map0;
    }

}

