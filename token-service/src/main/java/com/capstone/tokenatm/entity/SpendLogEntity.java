package com.capstone.tokenatm.entity;

import java.util.Date;

import javax.persistence.*;

@Entity // This tells Hibernate to make a table out of this class
@Table(indexes = @Index(name="id_idx", columnList = "user_id"))
public class SpendLogEntity {
	@Id
	@GeneratedValue(strategy=GenerationType.AUTO)
	private Integer id;

	private String user_id;

	private String user_name;

	public String getUser_name() {
		return user_name;
	}

	public void setUser_name(String user_name) {
		this.user_name = user_name;
	}

	private String type;

	private Integer token_count;

	private String source;

	private Date timestamp;

	public String getUserId() {
		return user_id;
	}
	public Integer getId() {
		return id;
	}

	public void setId(Integer id) {
		this.id = id;
	}

	public void setUser_id(String user_id) {
		this.user_id = user_id;
	}

	public String getType() {
		return this.type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public Integer getTokenCount() {
		return this.token_count;
	}

	public void setTokenCount(Integer token_count) {
		this.token_count = token_count;
	}

	public String getSource() {
		return this.source;
	}

	public void setSourcee(String source) {
		this.source = source;
	}
	public void setTimestamp(Date current_time) {
		this.timestamp = current_time;
	}

	public Date getTimestamp() {
		return this.timestamp;
	}

}
