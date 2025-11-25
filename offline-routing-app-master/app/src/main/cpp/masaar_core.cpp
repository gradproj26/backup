// masaar_core.cpp
#include "masaar_core.h"
#include <string>

static std::string node_id = "unknown";

void setNodeId(const std::string& id) {
    node_id = id;
}

std::string buildMessage(const std::string& payload, const std::string& dst) {
    std::string json = R"({"type":"DATA","src":")" + node_id +
                       R"(","dst":")" + dst +
                       R"(","payload":")" + payload + R"("})";
    return json;
}

std::string handleIncoming(const std::string& jsonMsg) {
    // هنا هتحطي parsing logic بعدين (تشيكي type, ACK, ... إلخ)
    return "Received: " + jsonMsg;
}
//
// Created by rawan on 10/8/2025.
//