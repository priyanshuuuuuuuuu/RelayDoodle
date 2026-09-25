module com.relaydoodle.client {
    requires javafx.controls;
    requires javafx.fxml;


    opens com.relaydoodle.client to javafx.fxml;
    exports com.relaydoodle.client;
}