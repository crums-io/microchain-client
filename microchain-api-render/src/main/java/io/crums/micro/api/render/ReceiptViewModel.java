/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.api.render;


import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import io.crums.micro.api.receipt.ChainReceipt;
import io.crums.micro.api.receipt.Receipt;
import io.crums.sldg.src.SourceRow;
import io.crums.sldg.src.SourceRowView;

/**
 * A {@link Receipt} to be rendered, paired with the {@link
 * ReceiptRenderSettings} controlling how its cells are displayed. A pure
 * argument type: carries no knowledge of Qute (or any other templating
 * engine) -- that plumbing lives entirely in the unexported {@code
 * io.crums.micro.api.render.engine} package.
 */
public final class ReceiptViewModel {

  private final Receipt receipt;
  private final ReceiptRenderSettings settings;

  public ReceiptViewModel(Receipt receipt) {
    this(receipt, new ReceiptRenderSettings());
  }

  public ReceiptViewModel(Receipt receipt, ReceiptRenderSettings settings) {
    this.receipt = decorate(receipt);
    this.settings = Objects.requireNonNull(settings, "settings");
  }

  public Receipt receipt() {
    return receipt;
  }

  public ReceiptRenderSettings settings() {
    return settings;
  }


  private static ChainReceipt decorate(ChainReceipt cr) {
    List<SourceRow> srcRows = cr.rows();
    SourceRow[] view = new SourceRow[srcRows.size()];
    for (int i = 0; i < view.length; ++i)
      view[i] = SourceRowView.map(srcRows.get(i));
    return new ChainReceipt(cr.chainUri(), Arrays.asList(view), cr.state());
  }

  private static Receipt decorate(Receipt receipt) {
    List<ChainReceipt> crs =
        receipt.chains().stream().map(ReceiptViewModel::decorate).toList();
    return new Receipt(crs);
  }

}
