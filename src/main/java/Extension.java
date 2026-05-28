import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.proxy.websocket.BinaryMessageReceivedAction;
import burp.api.montoya.proxy.websocket.BinaryMessageToBeSentAction;
import burp.api.montoya.proxy.websocket.InterceptedBinaryMessage;
import burp.api.montoya.proxy.websocket.InterceptedTextMessage;
import burp.api.montoya.proxy.websocket.ProxyMessageHandler;
import burp.api.montoya.proxy.websocket.ProxyWebSocketCreation;
import burp.api.montoya.proxy.websocket.ProxyWebSocketCreationHandler;
import burp.api.montoya.proxy.websocket.TextMessageReceivedAction;
import burp.api.montoya.proxy.websocket.TextMessageToBeSentAction;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.Value;

public class Extension implements BurpExtension {
    @Override
    public void initialize(MontoyaApi montoyaApi) {
        montoyaApi.extension().setName("MsgPack Socket Unpacker");
        montoyaApi.proxy().registerWebSocketCreationHandler(new MsgPackWebSocketCreationHandler());
    }

    private static final class MsgPackWebSocketCreationHandler implements ProxyWebSocketCreationHandler {
        @Override
        public void handleWebSocketCreation(ProxyWebSocketCreation webSocketCreation) {
            webSocketCreation.proxyWebSocket().registerProxyMessageHandler(new MsgPackProxyMessageHandler());
        }
    }

    private static final class MsgPackProxyMessageHandler implements ProxyMessageHandler {
        @Override
        public TextMessageReceivedAction handleTextMessageReceived(InterceptedTextMessage interceptedTextMessage) {
            return TextMessageReceivedAction.continueWith(interceptedTextMessage.payload());
        }

        @Override
        public TextMessageToBeSentAction handleTextMessageToBeSent(InterceptedTextMessage interceptedTextMessage) {
            return TextMessageToBeSentAction.continueWith(interceptedTextMessage.payload());
        }

        @Override
        public BinaryMessageReceivedAction handleBinaryMessageReceived(InterceptedBinaryMessage interceptedBinaryMessage) {
            ByteArray payload = interceptedBinaryMessage.payload();
            String decoded = tryDecodeMessagePack(payload);
            if (decoded != null) {
                appendDecodedNotes(interceptedBinaryMessage, decoded);
            }

            return BinaryMessageReceivedAction.continueWith(payload);
        }

        @Override
        public BinaryMessageToBeSentAction handleBinaryMessageToBeSent(InterceptedBinaryMessage interceptedBinaryMessage) {
            return BinaryMessageToBeSentAction.continueWith(interceptedBinaryMessage.payload());
        }
    }

    private static String tryDecodeMessagePack(ByteArray payload) {
        if (payload == null) {
            return null;
        }

        int length = payload.length();
        if (length == 0) {
            return null;
        }

        byte[] bytes = payload.getBytes();
        if ((bytes[0] & 0xFF) == 0xC1) {
            return null;
        }

        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(bytes)) {
            Value first = unpacker.unpackValue();
            if (!unpacker.hasNext()) {
                return first.toJson();
            }

            StringBuilder json = new StringBuilder((int) (length * 1.4)); // +40% as a rough estimate for JSON expansion
            json.append('[').append(first.toJson());

            while (unpacker.hasNext()) {
                json.append(',').append(unpacker.unpackValue().toJson());
            }
            json.append(']');
            return json.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void appendDecodedNotes(InterceptedBinaryMessage interceptedBinaryMessage, String decoded) {
        if (decoded == null || decoded.isEmpty()) {
            return;
        }

        String existing = interceptedBinaryMessage.annotations().notes();

        if (existing == null || existing.isBlank()) {
            interceptedBinaryMessage.annotations().setNotes(decoded);
            return;
        }

        interceptedBinaryMessage.annotations().setNotes(existing + "\n" + decoded);
    }
}