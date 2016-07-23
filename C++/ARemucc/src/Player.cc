#include <iostream>

#include "Player.h"
#include "path.h"

void Player::set_dir(std::string dir)
//-----------------------------------
{
   directory = filesystem::path(dir).make_absolute();
   if (! directory.exists())
   {
      log << "Recording directory " << dir << " not found." << std::endl;
      std::cerr << log.str();
      std::cerr.flush();
      throw std::runtime_error(log.str());
   }
   if (! directory.is_directory())
   {
      log << std::string("Recording directory ") << dir << " not a directory" << std::endl;
      std::cerr << log.str();
      std::cerr.flush();
      throw std::runtime_error(log.str());
   }
}


