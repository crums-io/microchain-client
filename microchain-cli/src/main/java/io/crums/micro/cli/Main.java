/*
 * Copyright 2026 Babak Farhang
 */
package io.crums.micro.cli;

import picocli.CommandLine;

/** Entry point — to be fleshed out. */
@CommandLine.Command(
    name = "microchain-cli",
    mixinStandardHelpOptions = true,
    description = "Commit on-prem ledger rows to a microchain.")
public class Main implements Runnable {

  public static void main(String[] args) {
    System.exit(new CommandLine(new Main()).execute(args));
  }

  @Override
  public void run() {
    System.out.println("Use --help for available commands.");
  }

}
