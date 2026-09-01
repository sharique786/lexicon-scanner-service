package com.db.macs3.ecomms.spectre.model;

import java.util.List;

/**
 * Represents the message(s) to scan, ALREADY resolved to plain text —
 * consistent with the original single-message design, file reading and
 * per-message length validation happen in {@code ScanController} (the same
 * place the original {@code MultipartFile} → text resolution always
 * happened), not in the orchestrator. This keeps
 * {@code LexiconScanOrchestrator} free of {@code IOException} handling and
 * HTTP-layer concerns entirely.
 *
 * <p>{@link SingleText} covers the original "paste one message" workflow —
 * always exactly one message, named {@code "Pasted Message"}.
 * {@link MultipleMessages} covers the newer multi-file-upload workflow: each
 * uploaded file becomes its own scanned message, named after its filename.
 */
public sealed interface MessageSource {

    /** One resolved message: an identifier (filename, or a generated label) plus its text. */
    record NamedMessageText(String messageId, String text) {}

    /** A single message pasted directly into the text area. */
    record SingleText(String text) implements MessageSource {}

    /** One or more resolved messages — typically one per uploaded file. */
    record MultipleMessages(List<NamedMessageText> messages) implements MessageSource {}
}
