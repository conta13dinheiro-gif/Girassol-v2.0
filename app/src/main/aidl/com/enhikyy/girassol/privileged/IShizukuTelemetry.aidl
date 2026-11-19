package com.enhikyy.girassol.privileged;

interface IShizukuTelemetry {
    String readTelemetry() = 1;
    void destroy() = 16777114;
}
