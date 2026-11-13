package fr.paybridge.mas.application.generation;

import fr.paybridge.mas.domain.generation.AgentResult;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

public record GenerationHandle(UUID taskId, CompletionStage<AgentResult> result, Runnable cancel) { }
