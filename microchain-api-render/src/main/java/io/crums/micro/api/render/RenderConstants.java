/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.render;

/**
 * Naming constants this module's own annotation conventions use --
 * {@code id}/attribute names and values {@link ReceiptHtmlCodec}/
 * {@link ReceiptPdfCodec} embed, plus this server's own {@code
 * crums:template-*} reference-tag names (the generic {@code embed}/
 * {@code embedCustomInfo} mechanism takes any caller-supplied names; these
 * are the specific ones this codebase uses). Centralized here, not left
 * as scattered literals, because a reader of a rendered page (a browser
 * extension, a verifier tool, a future {@code bindle}) needs these exact
 * strings to find the data back -- same reason {@code ApiConstants} exists
 * for this project's JSON field names, just for this module's own
 * HTML/PDF-annotation vocabulary instead.
 */
public final class RenderConstants {

  // The embedded receipt data block -- see
  // ReceiptHtmlCodec#embed(String,ReceiptRenderData) / ReceiptPdfCodec#embedReceiptAttachment.
  public static final String RECEIPT_SCRIPT_ID       = "crums-receipt";
  public static final String RECEIPT_ATTACHMENT_NAME = "receipt.json";

  // The embedded Qute template-source block -- optional; see
  // ReceiptHtmlCodec#embed(String,ReceiptRenderData) / ReceiptPdfCodec#embedTemplateAttachment.
  public static final String TEMPLATE_SCRIPT_ID       = "crums-template";
  public static final String TEMPLATE_ATTACHMENT_NAME = "template.txt";

  // The data-crums-kind attribute (HTML only) marking an embedded
  // <script>/<meta> element's semantic kind -- lets an extractor (see
  // ReceiptHtmlExtractor) tell these elements apart from anything a
  // template's own content happens to render, e.g. its own <meta
  // name="viewport"> in a real <head>.
  public static final String KIND_ATTR     = "data-crums-kind";
  public static final String KIND_RECEIPT  = "receipt";
  public static final String KIND_TEMPLATE = "template";
  public static final String KIND_REF      = "ref";

  // crums:template-* reference tags identifying which published template
  // version produced a rendering -- see ReceiptRenderService (microchain-
  // webservices) for where these are actually populated.
  public static final String TEMPLATE_OWNER_REF   = "crums:template-owner";
  public static final String TEMPLATE_NAME_REF    = "crums:template-name";
  public static final String TEMPLATE_VERSION_REF = "crums:template-version";
  public static final String TEMPLATE_SRC_REF     = "crums:template-src";

  private RenderConstants() { }
}
