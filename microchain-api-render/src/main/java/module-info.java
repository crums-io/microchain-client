module io.crums.micro.api.render {

  requires transitive io.crums.micro.api;

  requires io.quarkus.qute;
  requires openhtmltopdf.pdfbox;
  requires org.apache.pdfbox;

  exports io.crums.micro.api.render;

}
