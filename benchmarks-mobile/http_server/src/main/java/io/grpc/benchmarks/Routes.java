package io.grpc.benchmarks;

import spark.Route;

/**
 * Created by davidcao on 6/21/16.
 */
public class Routes {

    public static Route postPayload() {
        return (req, res) -> {
            String payload = req.body();

            if (payload != null && payload.length() > 0) {
                return "{\"payload\":" + payload + "}";
            } else {
                return "{\"Error\":\"This is an error message\"}";
            }
        };
    }

}
