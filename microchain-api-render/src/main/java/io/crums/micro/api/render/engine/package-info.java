/*
 * Copyright 2026 Babak Farhang
 */

/**
 * The Qute {@code Engine}-wiring tooling behind {@link
 * io.crums.micro.api.render.RenderingBuilder} -- not exported, deliberately:
 * Qute is the implementation behind this module's rendering DSL, not part
 * of the DSL itself, so an ordinary caller using {@code RenderingBuilder}
 * never needs to see it. It's here for one thing only: a caller that wants
 * a different {@code Engine} configuration than the builder gives has to
 * change this package, not reach into it from outside.
 *
 * <ul>
 * <li>{@link io.crums.micro.api.render.engine.QutePreprocesser} -- builds
 *     an {@code Engine} wired for rendering a {@code Receipt}: the
 *     reflection-based value resolver, the one virtual {@code
 *     cell.display} property, and (in the guarded variant) the
 *     access-budget trace listener.</li>
 * <li>{@link io.crums.micro.api.render.engine.CappedRenderer} -- renders a
 *     {@code TemplateInstance} so output can be aborted mid-stream the
 *     moment it crosses a character budget.</li>
 * </ul>
 */
package io.crums.micro.api.render.engine;
