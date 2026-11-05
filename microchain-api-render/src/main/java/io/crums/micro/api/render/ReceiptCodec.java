/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.render;

import java.util.Optional;

/**
 * Methods common to both {@link ReceiptHtmlCodec} and {@link ReceiptPdfCodec}.
 * In practice, those classes are used directly, not thru this interface. This
 * then, is more of a documentation-interface.
 */
public interface ReceiptCodec {

    /**
     * Embeds the document with the given receipt data and returns the modified
     * result.
     * 
     * @param doc   the document in its native format
     * @param data  the render-data to be embedded;
     *              presently the templateContent is not embedded (TODO)
     * @return      modified version of the document (in same native format)
     * 
     * @throws RenderException if there's an error
     */
    public byte[] embed(byte[] doc, ReceiptRenderData data) throws RenderException;

    /**
     * Extracts and return receipt data from the given document, if any.
     * 
     * @param doc   the document in its native format
     */
    public Optional<ReceiptRenderData> extract(byte[] doc);

}
