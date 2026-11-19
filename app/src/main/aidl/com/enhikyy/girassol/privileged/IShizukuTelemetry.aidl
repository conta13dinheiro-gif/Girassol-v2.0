package com.enhikyy.girassol.privileged;

interface IShizukuTelemetry {
    String readTelemetry();
    void destroy() = 16777114;
}
