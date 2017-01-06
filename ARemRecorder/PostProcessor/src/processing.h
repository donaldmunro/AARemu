#pragma once

#include <fstream>
#include <memory>
#include <unordered_map>

#include "path.h"

bool process_orientation(filesystem::path dir, std::unordered_map<std::string, std::string> headers,
                         std::string recording_type, std::string& orientationFile, int& count);
bool convert_frames(filesystem::path dir, std::unordered_map<std::string, std::string> headers,
                    std::string recording_type, int w, int h, int yuvSize, int rgbaSize,
                    int& shift_totalx, int& shift_totaly, int& framecount);