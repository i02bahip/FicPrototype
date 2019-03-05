package com.development.fic.ficprototype;

public class Motor {

    private float forward = 0;
    private float reverse = 0;

    public Motor() {
    }

    public float getForward() {
        return forward;
    }

    public void setForward(float forward) {
        this.forward = forward;
    }

    public float getReverse() {
        return reverse;
    }

    public void setReverse(float reverse) {
        this.reverse = reverse;
    }

    public String getStrForward() { return String.format("%.1f", this.forward); }

    public String getStrReverse() {
        return String.format("%.1f", this.reverse);
    }
}
