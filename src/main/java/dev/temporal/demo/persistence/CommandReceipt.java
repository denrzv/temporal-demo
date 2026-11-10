package dev.temporal.demo.persistence;
import jakarta.persistence.*;
@Entity @Table(name = "command_receipts")
public class CommandReceipt {
    @Id public String id;
    @Lob public String response;
    public CommandReceipt() {}
}
