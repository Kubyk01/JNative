package io.github.kubyk01.application.service.codegen.llvm;

import io.github.kubyk01.domain.analyzer.ir.Value;
import java.util.HashMap;
import java.util.Map;

public class LlvmValueMapper {
    private final Map<Value, String> valueMap = new HashMap<>();

    public void setValue(Value v, String name) { valueMap.put(v, name); }
    public String getValue(Value v) { return valueMap.get(v); }
    public void clear() { valueMap.clear(); }
}
