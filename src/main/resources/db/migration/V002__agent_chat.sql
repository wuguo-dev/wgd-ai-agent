CREATE TABLE public.agent_conversation (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES public.community_users(id),
    title VARCHAR(100) NOT NULL CHECK (length(title) > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX agent_conversation_owner_updated ON public.agent_conversation(user_id, updated_at DESC, id);
CREATE TABLE public.agent_message (
    sequence BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id UUID NOT NULL UNIQUE,
    conversation_id UUID NOT NULL REFERENCES public.agent_conversation(id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL CHECK (role IN ('user', 'assistant')),
    content TEXT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('completed','generating','failed','cancelled','interrupted')),
    citations JSONB NOT NULL DEFAULT '[]',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX agent_message_conversation_sequence ON public.agent_message(conversation_id, sequence DESC);
