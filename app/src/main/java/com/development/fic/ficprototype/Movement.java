package com.development.fic.ficprototype;

public class Movement {
    private Motor motorRight = new Motor();
    private Motor motorLeft = new Motor();

    public Movement() {
    }

    public void setMovement(float x, float y) {
        float yAxis = Math.abs(y);
        float xAxis = Math.abs(x);

        if(x == 0 && y == 0) {
            motorLeft.setForward(0);
            motorRight.setForward(0);
            motorLeft.setReverse(0);
            motorRight.setReverse(0);
        } else {
            //Solo hay información adelante o atrás.
            if(x == 0 && y != 0) {
                yAxis = Math.abs(y);
                if(y < 0) {
                    motorLeft.setForward(yAxis);
                    motorRight.setForward(yAxis);
                    motorLeft.setReverse(0);
                    motorRight.setReverse(0);
                } else {
                    motorLeft.setForward(0);
                    motorRight.setForward(0);
                    motorLeft.setReverse(yAxis);
                    motorRight.setReverse(yAxis);
                }
            } else {
                //Solo hay información hacia los lados.
                if(x != 0 && y == 0) {
                    xAxis = Math.abs(x);
                    if(x < 0) {
                        motorLeft.setForward(0);
                        motorRight.setForward(xAxis);
                        motorLeft.setReverse(xAxis);
                        motorRight.setReverse(0);
                    } else {
                        motorLeft.setForward(xAxis);
                        motorRight.setForward(0);
                        motorLeft.setReverse(0);
                        motorRight.setReverse(xAxis);
                    }
                } else {
                    //Hay información de movimiento adelante/atras/lateral
                    if(y < 0) {
                        //Hacia adelante
                        if(x < 0) {
                            //Hacia adelante e izquierda
                            motorLeft.setForward(0.4f);
                            motorRight.setForward(0.8f);
                            motorLeft.setReverse(0);
                            motorRight.setReverse(0);
                        } else {
                            // Hacia adelante y derecha
                            motorLeft.setForward(0.8f);
                            motorRight.setForward(0.4f);
                            motorLeft.setReverse(0);
                            motorRight.setReverse(0);
                        }

                    } else {
                        //Hacia atras
                        if(x < 0) {
                            //Hacia atras e izquierda
                            motorLeft.setForward(0);
                            motorRight.setForward(0);
                            motorLeft.setReverse(0.4f);
                            motorRight.setReverse(0.8f);
                        } else {
                            // Hacia atras y derecha
                            motorLeft.setForward(0);
                            motorRight.setForward(0);
                            motorLeft.setReverse(0.8f);
                            motorRight.setReverse(0.4f);
                        }
                    }
                }
            }
        }

    }

    public Motor getMotorRight() {
        return motorRight;
    }

    public Motor getMotorLeft() {
        return motorLeft;
    }

}
