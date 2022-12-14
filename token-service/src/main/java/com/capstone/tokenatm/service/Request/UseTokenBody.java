package com.capstone.tokenatm.service.Request;

public class UseTokenBody {

    private String assignment_id;
    private Integer token_count;

    public String getAssignment_id() {
        return assignment_id;
    }

    public void setAssignment_id(String assignment_id) {
        this.assignment_id = assignment_id;
    }

    public Integer getToken_count() {
        return token_count;
    }

    public void setToken_count(Integer token_count) {
        this.token_count = token_count;
    }

    public UseTokenBody(String assignment_id, Integer token_count) {
        this.assignment_id = assignment_id;
        this.token_count = token_count;
    }
}
