package com.development.fic.ficprototype;

public class Payload {
    private Float firstAzimuth;
    private float azimuth;
    private float pitch;
    private float roll;
    private Movement movement = new Movement();

    public Payload() {
    }

    public Float getFirstAzimuth() {
        return firstAzimuth;
    }

    public void setFirstAzimuth(Float firstAzimuth) {
        this.firstAzimuth = firstAzimuth;
    }

    public float getAzimuth() {
        return azimuth;
    }

    /**
     * Clase que convierte el dato azimuth obtenido de android a I2C
     * @return
     */
    public String getServoAzimuth() {
        //Convertir azimuth en 0-360 grados
        Float servoAzimuth = (this.azimuth  - this.firstAzimuth) + 90;
        //Float servoAzimuthFloat = (servoAzimuth + 90) % 360;
        //Convertir angulo inicial, sea cual sea, en 90 grados

        servoAzimuth = ( servoAzimuth  + 360 ) % 360;
        Integer checkLimits = Math.round(servoAzimuth);

        if(checkLimits > 180 && checkLimits <= 270) {
            servoAzimuth = 180.f;
        }

        if(checkLimits > 270 && checkLimits <= 360) {
            servoAzimuth = 0.001f;
        }


        //Calculos para I2C
        //0 grados == 540
        //180 grados == 110

        servoAzimuth = (servoAzimuth * 215);

        if(servoAzimuth == 0.f){
            servoAzimuth = 0.001f;
        }

        servoAzimuth = servoAzimuth / 90;
        servoAzimuth = Math.abs(servoAzimuth - 540);

        //Corregir límites 0 - 180 grados
        if(servoAzimuth > 540) {
            servoAzimuth = 540.f;
        }

        if(servoAzimuth < 110) {
            servoAzimuth = 110.f;
        }

        return Integer.toString(Math.round(servoAzimuth));
    }

    public void setAzimuth(float azimuth) {
        this.azimuth = azimuth;
    }

    public float getPitch() {
        return pitch;
    }

    public void setPitch(float pitch) {
        this.pitch = pitch;
    }

    public float getRoll() {
        return roll;
    }

    public void setRoll(float roll) {
        this.roll = roll;
    }

    public String getServoRoll() {
        Float servoRollFloat = Math.abs(this.roll) % 180;

        if(servoRollFloat == 0.f){
            servoRollFloat = 0.001f;
        }

        //Calculos para I2C
        //180 grados == 540
        //0 grados == 110
        servoRollFloat = (servoRollFloat * 215) / 90;
        servoRollFloat = Math.abs(servoRollFloat - 540);

        //Corregir límites 0 - 180 grados
        if(servoRollFloat > 540) {
            servoRollFloat = 540.f;
        }

        if(servoRollFloat < 110) {
            servoRollFloat = 110.f;
        }

        return Integer.toString(Math.round(servoRollFloat));
    }

    public Movement getMovement() {
        return movement;
    }

    public void setMovement(Movement movement) {
        this.movement = movement;
    }
}
