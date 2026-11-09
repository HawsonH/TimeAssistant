/**
 * AI 操作计划
 *
 * 从 AI 响应解析出的结构化提案：自然语言说明 + 待确认的操作列表，本身不包含执行逻辑。
 */
package com.hawson.timeassistant.ai;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A validated proposal returned by an AI service.
 *
 * <p>This class deliberately contains no persistence or execution methods. Callers must present
 * {@link #actions} to the user and only apply them after an explicit confirmation.</p>
 */
public final class AiPlan {
    private static final int MAX_MESSAGE_LENGTH = 20_000;
    private static final int MAX_ACTIONS = 100;
    private static final int MAX_READ_REQUESTS = 10;

    public final String message;
    public final boolean needsClarification;
    public final List<Action> actions;
    public final List<ReadRequest> readRequests;
    /** The exact JSON object extracted from the assistant content, excluding Markdown fences. */
    public final String rawJson;

    private AiPlan(String message, boolean needsClarification, List<Action> actions,
                   List<ReadRequest> readRequests, String rawJson) {
        this.message = message;
        this.needsClarification = needsClarification;
        this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
        this.readRequests = Collections.unmodifiableList(new ArrayList<>(readRequests));
        this.rawJson = rawJson;
    }

    /** Parses a raw JSON plan or a plan wrapped in a {@code ```json} Markdown fence. */
    public static AiPlan parse(String assistantContent) throws ParseException {
        String json = extractJson(assistantContent);
        final MiniJson.Document document;
        try {
            document = MiniJson.parse(json);
        } catch (MiniJson.JsonException error) {
            throw new ParseException("计划 JSON 无效：" + error.getMessage(), error);
        }

        Map<String, MiniJson.Node> root = requireObject(document.root, "计划");
        String message = requireNonBlankString(root, "message", "计划");
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new ParseException("计划 message 过长");
        }
        boolean needsClarification = requireBoolean(root, "needsClarification", "计划");
        List<MiniJson.Node> actionNodes = requireArray(root, "actions", "计划");
        if (actionNodes.size() > MAX_ACTIONS) {
            throw new ParseException("计划 actions 数量超过 " + MAX_ACTIONS);
        }

        List<Action> actions = new ArrayList<>(actionNodes.size());
        for (int index = 0; index < actionNodes.size(); index++) {
            MiniJson.Node actionNode = actionNodes.get(index);
            Map<String, MiniJson.Node> actionObject = requireObject(actionNode, "actions[" + index + "]");
            String wireType = requireNonBlankString(actionObject, "type", "actions[" + index + "]");
            Action.Type type = Action.Type.fromWireValue(wireType);
            if (type == null) {
                throw new ParseException("actions[" + index + "] 包含未知操作：" + wireType);
            }
            validateAction(type, actionObject, index);
            validateTimestampFields(actionObject, "actions[" + index + "]");

            @SuppressWarnings("unchecked")
            Map<String, Object> fields = (Map<String, Object>) MiniJson.toImmutableJavaValue(actionNode);
            actions.add(new Action(type, fields, document.source.substring(actionNode.start, actionNode.end)));
        }

        List<ReadRequest> readRequests = new ArrayList<>();
        MiniJson.Node readRequestsNode = root.get("readRequests");
        if (readRequestsNode != null) {
            if (!(readRequestsNode.value instanceof List)) {
                throw new ParseException("计划.readRequests 必须是数组");
            }
            @SuppressWarnings("unchecked")
            List<MiniJson.Node> nodes = (List<MiniJson.Node>) readRequestsNode.value;
            if (nodes.size() > MAX_READ_REQUESTS) {
                throw new ParseException("计划 readRequests 数量超过 " + MAX_READ_REQUESTS);
            }
            for (int index = 0; index < nodes.size(); index++) {
                Map<String, MiniJson.Node> object = requireObject(nodes.get(index),
                        "readRequests[" + index + "]");
                String requestId = requireNonBlankString(object, "requestId",
                        "readRequests[" + index + "]");
                String type = requireNonBlankString(object, "type",
                        "readRequests[" + index + "]");
                MiniJson.Node argsNode = object.get("args");
                Map<String, Object> args;
                if (argsNode == null) {
                    args = Collections.emptyMap();
                } else {
                    requireObject(argsNode, "readRequests[" + index + "].args");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> converted = (Map<String, Object>)
                            MiniJson.toImmutableJavaValue(argsNode);
                    args = converted;
                }
                readRequests.add(new ReadRequest(requestId, type, args));
            }
        }

        if (needsClarification && !actions.isEmpty()) {
            throw new ParseException("needsClarification 为 true 时 actions 必须为空");
        }
        if (needsClarification && !readRequests.isEmpty()) {
            throw new ParseException("needsClarification 为 true 时 readRequests 必须为空");
        }
        if (!actions.isEmpty() && !readRequests.isEmpty()) {
            throw new ParseException("readRequests 与 actions 不能同时出现");
        }
        return new AiPlan(message, needsClarification, actions, readRequests, document.source);
    }

    public String getMessage() {
        return message;
    }

    public boolean needsClarification() {
        return needsClarification;
    }

    public List<Action> getActions() {
        return actions;
    }

    public List<ReadRequest> getReadRequests() { return readRequests; }

    public boolean needsContextRead() { return !readRequests.isEmpty(); }

    public String getRawJson() {
        return rawJson;
    }

    /** Creates an executable view containing only the selected actions from this proposal. */
    public AiPlan withActions(List<Action> selectedActions) {
        if (selectedActions == null || selectedActions.isEmpty()) {
            throw new IllegalArgumentException("selectedActions must not be empty");
        }
        return new AiPlan(message, false, selectedActions, Collections.emptyList(), rawJson);
    }

    private static void validateAction(Action.Type type, Map<String, MiniJson.Node> object, int index)
            throws ParseException {
        String context = "actions[" + index + "]";
        switch (type) {
            case CREATE_TASK:
                requireNonBlankString(object, "title", context);
                optionalPositiveInteger(object, context, "estimatedMinutes", "estimated_minutes");
                validateSubtasks(object.get("subtasks"), context + ".subtasks", false);
                break;
            case UPDATE_TASK:
                requireTaskId(object, context);
                Map<String, MiniJson.Node> changes = requireObjectField(object, "changes", context);
                if (changes.isEmpty()) throw new ParseException(context + ".changes 不能为空");
                if (changes.containsKey("status")) {
                    throw new ParseException(context
                            + ".changes.status 不受支持；请使用 complete_task 或 cancel_task");
                }
                break;
            case DELETE_TASK:
            case CANCEL_TASK:
            case COMPLETE_TASK:
                requireTaskId(object, context);
                break;
            case SCHEDULE_TASK:
                requireTaskId(object, context);
                requireStringOrNumber(object, context, "startAt", "start_at");
                requirePositiveInteger(object, context, "durationMinutes", "duration_minutes");
                break;
            case SPLIT_TASK:
                requireTaskId(object, context);
                validateSubtasks(object.get("subtasks"), context + ".subtasks", true);
                break;
            case START_FOCUS:
                requireTaskId(object, context);
                optionalPositiveInteger(object, context, "durationMinutes", "duration_minutes");
                break;
            case SET_GREETING:
                String greeting = requireNonBlankString(object, "text", context);
                if (greeting.length() > 32) {
                    throw new ParseException(context + ".text 不能超过 32 个字符");
                }
                break;
            case CREATE_RECURRING_GROUP:
                requireNonBlankString(object, "title", context);
                requireStringOrNumber(object, context, "startAt", "start_at");
                requirePositiveInteger(object, context, "durationMinutes", "duration_minutes");
                break;
            case UPDATE_RECURRING_GROUP:
                requireNonBlankString(object, "groupId", context);
                Map<String, MiniJson.Node> recurringChanges =
                        requireObjectField(object, "changes", context);
                if (recurringChanges.isEmpty()) {
                    throw new ParseException(context + ".changes 不能为空");
                }
                break;
            case STOP_RECURRING_GROUP:
                requireNonBlankString(object, "groupId", context);
                break;
            case SET_RECURRING_OVERRIDE:
                requireNonBlankString(object, "groupId", context);
                requireStringOrNumber(object, context, "occurrenceAt", "occurrence_at");
                requireNonBlankString(object, "operation", context);
                break;
            case CREATE_PREFERENCE:
                requireNonBlankString(object, "preferenceType", context);
                requireNonBlankString(object, "scopeType", context);
                requireMinute(object, context, "startMinute");
                requireMinute(object, context, "endMinute");
                break;
            case UPDATE_PREFERENCE:
                requireNonBlankString(object, "preferenceId", context);
                Map<String, MiniJson.Node> preferenceChanges =
                        requireObjectField(object, "changes", context);
                if (preferenceChanges.isEmpty()) throw new ParseException(context + ".changes 不能为空");
                break;
            case DELETE_PREFERENCE:
            case ACTIVATE_PREFERENCE:
                requireNonBlankString(object, "preferenceId", context);
                break;
            case COMPOSE_CALENDAR_EVENT:
                requireNonBlankString(object, "title", context);
                requireStringOrNumber(object, context, "startAt", "start_at");
                requireStringOrNumber(object, context, "endAt", "end_at");
                break;
            default:
                throw new ParseException(context + " 操作不受支持");
        }
    }

    private static void validateSubtasks(MiniJson.Node subtasksNode, String context,
                                         boolean required) throws ParseException {
        if (subtasksNode == null) {
            if (required) throw new ParseException(context + " 必须是非空数组");
            return;
        }
        if (!(subtasksNode.value instanceof List)) {
            throw new ParseException(context + " 必须是非空数组");
        }
        @SuppressWarnings("unchecked")
        List<MiniJson.Node> subtasks = (List<MiniJson.Node>) subtasksNode.value;
        if (subtasks.isEmpty()) throw new ParseException(context + " 必须是非空数组");
        for (int childIndex = 0; childIndex < subtasks.size(); childIndex++) {
            MiniJson.Node child = subtasks.get(childIndex);
            if (child.value instanceof String && !((String) child.value).trim().isEmpty()) continue;
            if (child.value instanceof Map) {
                Map<String, MiniJson.Node> childObject = requireObject(child,
                        context + "[" + childIndex + "]");
                requireNonBlankString(childObject, "title", context + "[" + childIndex + "]");
                continue;
            }
            throw new ParseException(context + "[" + childIndex
                    + "] 必须是非空标题或包含 title 的对象");
        }
    }

    private static String requireTaskId(Map<String, MiniJson.Node> object, String context)
            throws ParseException {
        MiniJson.Node node = firstPresent(object, "taskId", "task_id", "id");
        if (node == null || !(node.value instanceof String) || ((String) node.value).trim().isEmpty()) {
            throw new ParseException(context + " 缺少非空 taskId");
        }
        return ((String) node.value).trim();
    }

    private static void requireStringOrNumber(Map<String, MiniJson.Node> object, String context,
                                              String... names) throws ParseException {
        MiniJson.Node node = firstPresent(object, names);
        if (node == null || (!(node.value instanceof String) && !(node.value instanceof BigDecimal))) {
            throw new ParseException(context + " 缺少 " + names[0] + "（字符串或数字）");
        }
        if (node.value instanceof String && ((String) node.value).trim().isEmpty()) {
            throw new ParseException(context + "." + names[0] + " 不能为空");
        }
    }

    private static void validateTimestampFields(Map<String, MiniJson.Node> object,
                                                String context) throws ParseException {
        for (Map.Entry<String, MiniJson.Node> entry : object.entrySet()) {
            MiniJson.Node node = entry.getValue();
            if (node == null || node.value == null) continue;
            String key = entry.getKey();
            if (isTimestampField(key)) validateTimestampValue(node.value, context + "." + key);
            if ("changes".equals(key) && node.value instanceof Map) {
                validateTimestampFields(requireObject(node, context + ".changes"),
                        context + ".changes");
            }
        }
    }

    private static boolean isTimestampField(String key) {
        return "reminderAt".equals(key) || "reminder_at".equals(key)
                || "dueAt".equals(key) || "due_at".equals(key) || "deadline".equals(key)
                || "scheduledStartAt".equals(key) || "scheduledEndAt".equals(key)
                || "startAt".equals(key) || "start_at".equals(key)
                || "endAt".equals(key) || "end_at".equals(key)
                || "occurrenceAt".equals(key) || "occurrence_at".equals(key)
                || "endsAt".equals(key);
    }

    private static void validateTimestampValue(Object value, String context)
            throws ParseException {
        if (value instanceof BigDecimal) {
            try {
                ((BigDecimal) value).longValueExact();
                return;
            } catch (ArithmeticException error) {
                throw new ParseException(context + " 必须是 epoch milliseconds 整数", error);
            }
        }
        if (!(value instanceof String)) {
            throw new ParseException(context
                    + " 必须是 epoch milliseconds 或带时区的 ISO-8601 字符串");
        }
        String raw = ((String) value).trim();
        if (raw.matches("[0-9]+")) return;
        if (!raw.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}"
                + "(?::[0-9]{2}(?:\\.[0-9]+)?)?(?:Z|[+-][0-9]{2}:[0-9]{2})")) {
            throw new ParseException(context
                    + " 时间字符串必须包含时区，例如 2026-08-12T17:30:00+08:00");
        }
    }

    private static void requirePositiveInteger(Map<String, MiniJson.Node> object, String context,
                                               String... names) throws ParseException {
        MiniJson.Node node = firstPresent(object, names);
        if (node == null || !isPositiveInteger(node.value)) {
            throw new ParseException(context + "." + names[0] + " 必须是正整数");
        }
    }

    private static void optionalPositiveInteger(Map<String, MiniJson.Node> object, String context,
                                                String... names) throws ParseException {
        MiniJson.Node node = firstPresent(object, names);
        if (node != null && !isPositiveInteger(node.value)) {
            throw new ParseException(context + "." + names[0] + " 必须是正整数");
        }
    }

    private static void requireMinute(Map<String, MiniJson.Node> object, String context,
                                      String name) throws ParseException {
        MiniJson.Node node = object.get(name);
        if (!(node != null && node.value instanceof BigDecimal)) {
            throw new ParseException(context + "." + name + " 必须是 0..1439 的整数");
        }
        BigDecimal number = (BigDecimal) node.value;
        if (number.stripTrailingZeros().scale() > 0 || number.signum() < 0
                || number.compareTo(BigDecimal.valueOf(1439)) > 0) {
            throw new ParseException(context + "." + name + " 必须是 0..1439 的整数");
        }
    }

    private static boolean isPositiveInteger(Object value) {
        if (!(value instanceof BigDecimal)) return false;
        BigDecimal number = (BigDecimal) value;
        return number.signum() > 0 && number.stripTrailingZeros().scale() <= 0;
    }

    private static MiniJson.Node firstPresent(Map<String, MiniJson.Node> object, String... names) {
        for (String name : names) {
            MiniJson.Node value = object.get(name);
            if (value != null) return value;
        }
        return null;
    }

    private static String extractJson(String content) throws ParseException {
        if (content == null || content.trim().isEmpty()) {
            throw new ParseException("AI 返回了空内容");
        }
        String trimmed = content.trim();
        int fenceStart = trimmed.indexOf("```");
        if (fenceStart < 0) return trimmed;

        int headerEnd = trimmed.indexOf('\n', fenceStart + 3);
        if (headerEnd < 0) throw new ParseException("JSON 代码块缺少换行或结束标记");
        String language = trimmed.substring(fenceStart + 3, headerEnd).trim();
        if (!language.isEmpty() && !"json".equalsIgnoreCase(language)) {
            throw new ParseException("不支持的代码块类型：" + language);
        }
        int fenceEnd = trimmed.indexOf("```", headerEnd + 1);
        if (fenceEnd < 0) throw new ParseException("JSON 代码块缺少结束标记");
        String json = trimmed.substring(headerEnd + 1, fenceEnd).trim();
        if (json.isEmpty()) throw new ParseException("JSON 代码块为空");
        return json;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, MiniJson.Node> requireObject(MiniJson.Node node, String context)
            throws ParseException {
        if (node == null || !(node.value instanceof Map)) {
            throw new ParseException(context + " 必须是 JSON 对象");
        }
        return (Map<String, MiniJson.Node>) node.value;
    }

    private static Map<String, MiniJson.Node> requireObjectField(Map<String, MiniJson.Node> object,
                                                                 String name, String context)
            throws ParseException {
        MiniJson.Node node = object.get(name);
        if (node == null) throw new ParseException(context + " 缺少 " + name);
        return requireObject(node, context + "." + name);
    }

    @SuppressWarnings("unchecked")
    private static List<MiniJson.Node> requireArray(Map<String, MiniJson.Node> object, String name,
                                                    String context) throws ParseException {
        MiniJson.Node node = object.get(name);
        if (node == null || !(node.value instanceof List)) {
            throw new ParseException(context + "." + name + " 必须是数组");
        }
        return (List<MiniJson.Node>) node.value;
    }

    private static String requireNonBlankString(Map<String, MiniJson.Node> object, String name,
                                                String context) throws ParseException {
        MiniJson.Node node = object.get(name);
        if (node == null || !(node.value instanceof String) || ((String) node.value).trim().isEmpty()) {
            throw new ParseException(context + "." + name + " 必须是非空字符串");
        }
        return ((String) node.value).trim();
    }

    private static boolean requireBoolean(Map<String, MiniJson.Node> object, String name,
                                          String context) throws ParseException {
        MiniJson.Node node = object.get(name);
        if (node == null || !(node.value instanceof Boolean)) {
            throw new ParseException(context + "." + name + " 必须是布尔值");
        }
        return (Boolean) node.value;
    }

    public static final class Action {
        public final Type type;
        /** All action fields, including {@code type}; maps and lists are deeply immutable. */
        public final Map<String, Object> fields;
        /** The exact JSON object for this action as returned by the model. */
        public final String rawJson;

        private Action(Type type, Map<String, Object> fields, String rawJson) {
            this.type = type;
            this.fields = fields;
            this.rawJson = rawJson;
        }

        public Type getType() {
            return type;
        }

        public String getWireType() {
            return type.wireValue;
        }

        public Map<String, Object> getFields() {
            return fields;
        }

        public Object get(String name) {
            return fields.get(name);
        }

        public String getRawJson() {
            return rawJson;
        }

        public enum Type {
            CREATE_TASK("create_task"),
            UPDATE_TASK("update_task"),
            DELETE_TASK("delete_task"),
            CANCEL_TASK("cancel_task"),
            SCHEDULE_TASK("schedule_task"),
            COMPLETE_TASK("complete_task"),
            SPLIT_TASK("split_task"),
            START_FOCUS("start_focus"),
            SET_GREETING("set_greeting"),
            CREATE_RECURRING_GROUP("create_recurring_group"),
            UPDATE_RECURRING_GROUP("update_recurring_group"),
            STOP_RECURRING_GROUP("stop_recurring_group"),
            SET_RECURRING_OVERRIDE("set_recurring_override"),
            CREATE_PREFERENCE("create_preference"),
            UPDATE_PREFERENCE("update_preference"),
            DELETE_PREFERENCE("delete_preference"),
            ACTIVATE_PREFERENCE("activate_preference"),
            COMPOSE_CALENDAR_EVENT("compose_calendar_event");

            public final String wireValue;

            Type(String wireValue) {
                this.wireValue = wireValue;
            }

            public String getWireValue() {
                return wireValue;
            }

            static Type fromWireValue(String value) {
                String normalized = value.toLowerCase(Locale.ROOT);
                for (Type type : values()) {
                    if (type.wireValue.equals(normalized)) return type;
                }
                return null;
            }
        }
    }

    public static final class ReadRequest {
        public final String requestId;
        public final String type;
        public final Map<String, Object> args;

        private ReadRequest(String requestId, String type, Map<String, Object> args) {
            this.requestId = requestId;
            this.type = type.toLowerCase(Locale.ROOT);
            this.args = Collections.unmodifiableMap(new LinkedHashMap<>(args));
        }

        public String getRequestId() { return requestId; }
        public String getType() { return type; }
        public Map<String, Object> getArgs() { return args; }
    }

    public static final class ParseException extends Exception {
        public ParseException(String message) {
            super(message);
        }

        ParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

/** A small strict JSON codec so JVM tests do not depend on Android's org.json stubs. */
final class MiniJson {
    private static final int MAX_INPUT_LENGTH = 1_048_576;
    private static final int MAX_DEPTH = 64;

    private MiniJson() {}

    static Document parse(String source) throws JsonException {
        if (source == null) throw new JsonException("输入为空");
        if (source.length() > MAX_INPUT_LENGTH) throw new JsonException("输入超过 1 MiB");
        Parser parser = new Parser(source);
        Node root = parser.parseValue(0);
        parser.skipWhitespace();
        if (!parser.isAtEnd()) throw parser.error("JSON 末尾存在多余内容");
        return new Document(source, root);
    }

    static String stringify(Object value) {
        StringBuilder output = new StringBuilder();
        writeValue(output, value);
        return output.toString();
    }

    static Object toImmutableJavaValue(Node node) {
        if (node.value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Node> object = (Map<String, Node>) node.value;
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Node> entry : object.entrySet()) {
                copy.put(entry.getKey(), toImmutableJavaValue(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (node.value instanceof List) {
            @SuppressWarnings("unchecked")
            List<Node> array = (List<Node>) node.value;
            List<Object> copy = new ArrayList<>(array.size());
            for (Node child : array) copy.add(toImmutableJavaValue(child));
            return Collections.unmodifiableList(copy);
        }
        return node.value;
    }

    private static void writeValue(StringBuilder output, Object value) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String) {
            writeString(output, (String) value);
        } else if (value instanceof Boolean || value instanceof BigDecimal
                || value instanceof Integer || value instanceof Long
                || value instanceof Double || value instanceof Float) {
            if ((value instanceof Double && !Double.isFinite((Double) value))
                    || (value instanceof Float && !Float.isFinite((Float) value))) {
                throw new IllegalArgumentException("JSON 数字必须是有限值");
            }
            output.append(value);
        } else if (value instanceof Map) {
            output.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    throw new IllegalArgumentException("JSON 对象键必须是字符串");
                }
                if (!first) output.append(',');
                first = false;
                writeString(output, (String) entry.getKey());
                output.append(':');
                writeValue(output, entry.getValue());
            }
            output.append('}');
        } else if (value instanceof Iterable) {
            output.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) output.append(',');
                first = false;
                writeValue(output, item);
            }
            output.append(']');
        } else {
            throw new IllegalArgumentException("不支持的 JSON 值类型：" + value.getClass().getName());
        }
    }

    private static void writeString(StringBuilder output, String value) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"': output.append("\\\""); break;
                case '\\': output.append("\\\\"); break;
                case '\b': output.append("\\b"); break;
                case '\f': output.append("\\f"); break;
                case '\n': output.append("\\n"); break;
                case '\r': output.append("\\r"); break;
                case '\t': output.append("\\t"); break;
                default:
                    if (character < 0x20) {
                        output.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        output.append(character);
                    }
            }
        }
        output.append('"');
    }

    static final class Document {
        final String source;
        final Node root;

        Document(String source, Node root) {
            this.source = source;
            this.root = root;
        }
    }

    static final class Node {
        final Object value;
        final int start;
        final int end;

        Node(Object value, int start, int end) {
            this.value = value;
            this.start = start;
            this.end = end;
        }
    }

    static final class JsonException extends Exception {
        JsonException(String message) {
            super(message);
        }
    }

    private static final class Parser {
        private final String source;
        private int position;

        Parser(String source) {
            this.source = source;
        }

        Node parseValue(int depth) throws JsonException {
            if (depth > MAX_DEPTH) throw error("JSON 嵌套过深");
            skipWhitespace();
            if (isAtEnd()) throw error("缺少 JSON 值");
            int start = position;
            char character = source.charAt(position);
            switch (character) {
                case '{': return parseObject(depth, start);
                case '[': return parseArray(depth, start);
                case '"': return new Node(parseString(), start, position);
                case 't': return parseLiteral("true", Boolean.TRUE, start);
                case 'f': return parseLiteral("false", Boolean.FALSE, start);
                case 'n': return parseLiteral("null", null, start);
                default:
                    if (character == '-' || (character >= '0' && character <= '9')) {
                        return parseNumber(start);
                    }
                    throw error("无法识别的 JSON 值");
            }
        }

        private Node parseObject(int depth, int start) throws JsonException {
            position++;
            skipWhitespace();
            Map<String, Node> values = new LinkedHashMap<>();
            if (consume('}')) return new Node(values, start, position);
            while (true) {
                skipWhitespace();
                if (isAtEnd() || source.charAt(position) != '"') {
                    throw error("JSON 对象键必须是字符串");
                }
                String key = parseString();
                if (values.containsKey(key)) throw error("JSON 对象包含重复键：" + key);
                skipWhitespace();
                if (!consume(':')) throw error("JSON 对象键后缺少冒号");
                values.put(key, parseValue(depth + 1));
                skipWhitespace();
                if (consume('}')) return new Node(values, start, position);
                if (!consume(',')) throw error("JSON 对象字段之间缺少逗号");
            }
        }

        private Node parseArray(int depth, int start) throws JsonException {
            position++;
            skipWhitespace();
            List<Node> values = new ArrayList<>();
            if (consume(']')) return new Node(values, start, position);
            while (true) {
                values.add(parseValue(depth + 1));
                skipWhitespace();
                if (consume(']')) return new Node(values, start, position);
                if (!consume(',')) throw error("JSON 数组元素之间缺少逗号");
            }
        }

        private String parseString() throws JsonException {
            position++;
            StringBuilder value = new StringBuilder();
            while (!isAtEnd()) {
                char character = source.charAt(position++);
                if (character == '"') return value.toString();
                if (character == '\\') {
                    if (isAtEnd()) throw error("JSON 字符串转义不完整");
                    char escaped = source.charAt(position++);
                    switch (escaped) {
                        case '"': value.append('"'); break;
                        case '\\': value.append('\\'); break;
                        case '/': value.append('/'); break;
                        case 'b': value.append('\b'); break;
                        case 'f': value.append('\f'); break;
                        case 'n': value.append('\n'); break;
                        case 'r': value.append('\r'); break;
                        case 't': value.append('\t'); break;
                        case 'u': value.append(parseUnicodeEscape()); break;
                        default: throw error("JSON 字符串包含非法转义");
                    }
                } else {
                    if (character < 0x20) throw error("JSON 字符串包含未转义控制字符");
                    value.append(character);
                }
            }
            throw error("JSON 字符串缺少结束引号");
        }

        private char parseUnicodeEscape() throws JsonException {
            if (position + 4 > source.length()) throw error("Unicode 转义不完整");
            int value = 0;
            for (int index = 0; index < 4; index++) {
                int digit = Character.digit(source.charAt(position++), 16);
                if (digit < 0) throw error("Unicode 转义包含非十六进制字符");
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private Node parseLiteral(String literal, Object value, int start) throws JsonException {
            if (!source.regionMatches(position, literal, 0, literal.length())) {
                throw error("非法 JSON 字面量");
            }
            position += literal.length();
            return new Node(value, start, position);
        }

        private Node parseNumber(int start) throws JsonException {
            if (consume('-') && isAtEnd()) throw error("JSON 数字不完整");
            if (consume('0')) {
                if (!isAtEnd() && Character.isDigit(source.charAt(position))) {
                    throw error("JSON 数字不能包含前导零");
                }
            } else {
                if (isAtEnd() || source.charAt(position) < '1' || source.charAt(position) > '9') {
                    throw error("JSON 数字整数部分无效");
                }
                while (!isAtEnd() && Character.isDigit(source.charAt(position))) position++;
            }
            if (consume('.')) {
                if (isAtEnd() || !Character.isDigit(source.charAt(position))) {
                    throw error("JSON 数字小数部分无效");
                }
                while (!isAtEnd() && Character.isDigit(source.charAt(position))) position++;
            }
            if (!isAtEnd() && (source.charAt(position) == 'e' || source.charAt(position) == 'E')) {
                position++;
                if (!isAtEnd() && (source.charAt(position) == '+' || source.charAt(position) == '-')) position++;
                if (isAtEnd() || !Character.isDigit(source.charAt(position))) {
                    throw error("JSON 数字指数部分无效");
                }
                while (!isAtEnd() && Character.isDigit(source.charAt(position))) position++;
            }
            try {
                return new Node(new BigDecimal(source.substring(start, position)), start, position);
            } catch (NumberFormatException error) {
                throw error("JSON 数字无效");
            }
        }

        void skipWhitespace() {
            while (!isAtEnd()) {
                char character = source.charAt(position);
                if (character == ' ' || character == '\n' || character == '\r' || character == '\t') position++;
                else break;
            }
        }

        boolean consume(char expected) {
            if (!isAtEnd() && source.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        boolean isAtEnd() {
            return position >= source.length();
        }

        JsonException error(String message) {
            return new JsonException(message + "（位置 " + position + "）");
        }
    }
}
