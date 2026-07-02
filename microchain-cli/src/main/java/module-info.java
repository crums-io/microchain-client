module io.crums.micro.cli {

  // micro.http re-exports micro.api → sldg.base + sldg.src.sql (sql-ledger, source-ledger, etc.)
  requires io.crums.micro.http;
  requires java.net.http;   // HttpClient.newHttpClient() — not re-exported by micro.http
  requires java.sql;        // Connection — not re-exported by micro.api
  requires info.picocli;

  exports io.crums.micro.cli;

}
