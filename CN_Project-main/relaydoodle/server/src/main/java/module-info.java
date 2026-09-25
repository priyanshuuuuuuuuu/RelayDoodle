module com.relaydoodle.server {
    requires javafx.controls;
    requires javafx.fxml;


    opens com.relaydoodle.server to javafx.fxml;
    exports com.relaydoodle.server;
}