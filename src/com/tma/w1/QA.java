package com.tma.w1;

import java.io.Serializable;

public class QA implements Serializable{
    private String question;
    private String answer;

    public void setQuestion(String q) {
	question = q;
    }

    public String getQuestion() {
	return question;
    }

    public void setAnswer(String a) {
	answer = a;
    }

    public String getAnswer() {
	return answer;
    }
}
