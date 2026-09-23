package repro;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "CHILD")
public class Child {
    public enum Status { ACTIVE, INACTIVE }

    @Id
    Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "PARENT_ID", nullable = false)
    Parent parent;

    @Enumerated(EnumType.ORDINAL)
    @Column(nullable = false)
    Status status;

    public Child() {
    }

    Child(Long id, Parent parent, Status status) {
        this.id = id;
        this.parent = parent;
        this.status = status;
    }
}
