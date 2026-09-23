package repro;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

@Entity
@Table(name = "PARENT")
public class Parent {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "parentSeq")
    @SequenceGenerator(name = "parentSeq", sequenceName = "PARENT_SEQ", allocationSize = 1)
    Long id;

    public Parent() {
    }
}
