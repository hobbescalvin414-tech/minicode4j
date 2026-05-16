package minicode.core.loop;

import minicode.core.message.ChatMessage;
import minicode.core.step.AgentStep;

import java.util.List;

@FunctionalInterface
public interface ModelAdapter {
    AgentStep next(List<ChatMessage> messages);
}
