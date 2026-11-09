/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api;

/**
 * Names reserved for server-managed system entities, never available to a
 * tenant's own chain-creation -- not just chain names: any entity path
 * living under the same {@link #SYS_PREFIX} namespace, e.g. a future
 * {@code sys/receipt_templates.src}-style resource path alongside the
 * chain name itself. Says nothing about usernames or any other namespace.
 *
 * <p>Not enforced by any check today: chain names don't yet allow {@code
 * '/'} at all (see each server's own name-validation pattern), so a
 * tenant-chosen name colliding with {@link #SYS_PREFIX} is already
 * structurally impossible. Once hierarchical (slash-containing) chain
 * names are allowed generally, tenant-chosen names will need to
 * explicitly reject this prefix -- there's no check to update before
 * then, only one to add.</p>
 */
public final class ReservedSystemNames {

  /** Reserved top-level segment for server-managed entities. */
  public static final String SYS_PREFIX = "sys";

  /**
   * The per-user receipt-templates chain's name in {@code chain_infos} --
   * also the storage key {@code ChainStorageService} commits/reads it
   * under. Distinct from the REST route it's published through ({@code
   * /{username}/_receipt_templates}), which keeps its own, unrelated
   * leading-underscore convention.
   */
  public static final String RECEIPT_TEMPLATES = SYS_PREFIX + "/receipt_templates";

  private ReservedSystemNames() { }
}
