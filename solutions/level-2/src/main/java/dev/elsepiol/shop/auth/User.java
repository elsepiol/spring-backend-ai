package dev.elsepiol.shop.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * "users" rather than "user": USER is a reserved word in PostgreSQL, so a table
 * with that name would need quoting everywhere.
 */
@Entity
@Table(name = "users")
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, updatable = false)
	private String email;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Enumerated(EnumType.STRING) // store "ADMIN", not 1: survives reordering the enum
	@Column(nullable = false)
	private Role role;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected User() {
	}

	public User(String email, String passwordHash, Role role) {
		this.email = email;
		this.passwordHash = passwordHash;
		this.role = role;
		this.createdAt = Instant.now();
	}

	public Long getId() {
		return id;
	}

	public String getEmail() {
		return email;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public Role getRole() {
		return role;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void changePasswordHash(String newHash) {
		this.passwordHash = newHash;
	}

}
