//
// Created by rawan on 10/9/2025.
//

#ifndef OFFLINEROUTINGAPP_MASAAR_CORE_H
#define OFFLINEROUTINGAPP_MASAAR_CORE_H

#include <string>

void setNodeId(const std::string& id);
std::string buildMessage(const std::string& payload, const std::string& dst);
std::string handleIncoming(const std::string& jsonMsg);

#endif //OFFLINEROUTINGAPP_MASAAR_CORE_H