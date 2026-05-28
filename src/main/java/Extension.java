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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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
            String decoded = tryDecodeMessagePack(payload.getBytes());
            if (decoded == null) {
                return BinaryMessageReceivedAction.continueWith(payload);
            }

            byte[] utf8Bytes = decoded.getBytes(StandardCharsets.UTF_8);
            return BinaryMessageReceivedAction.continueWith(ByteArray.byteArray(utf8Bytes));
        }

        @Override
        public BinaryMessageToBeSentAction handleBinaryMessageToBeSent(InterceptedBinaryMessage interceptedBinaryMessage) {
            return BinaryMessageToBeSentAction.continueWith(interceptedBinaryMessage.payload());
        }
    }

    private static String tryDecodeMessagePack(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return null;
        }

        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(payload)) {
            List<Value> values = new ArrayList<>();
            while (unpacker.hasNext()) {
                values.add(unpacker.unpackValue());
            }

            if (values.isEmpty()) {
                return null;
            }

            if (values.size() == 1) {
                return values.get(0).toJson();
            }

            StringBuilder json = new StringBuilder(2 + (values.size() * 16));
            json.append('[');
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    json.append(',');
                }
                json.append(values.get(i).toJson());
            }
            json.append(']');
            return json.toString();
        } catch (Exception ignored) {
            return null;
        }
    }
}