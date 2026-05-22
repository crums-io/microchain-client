module io.crums.micro.cli {

  requires io.crums.micro.api;
  requires io.crums.sldg.src.sql;   // transitively brings in source-ledger + jsonimple
  requires java.net.http;
  requires info.picocli;

  exports io.crums.micro.cli;

}
