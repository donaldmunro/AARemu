#include <stdlib.h>
//#if defined(_WIN32) || defined(_WIN64)
//#include <process.h>
//#define WIFEXITED(w)    (((w) & 0XFFFFFF00) == 0)
//#define WIFSIGNALED(w)  (!WIFEXITED(w))
//#define WEXITSTATUS(w)  (w)
//#define WTERMSIG(w)     (w)
//#else
#include <unistd.h>
#include <math.h>
#include <sys/wait.h>
//#endif

#include <iostream>
#include <string>
#include <iomanip>
#include <vector>
#include <unordered_map>
#include <utility>
#include <algorithm>
#include <fstream>
#include <limits>

#include "optionparser.h" //http://optionparser.sourceforge.net/index.html (file http://optionparser.sourceforge.net/optionparser.h)
#include "path.h"
#include "util.h"
#include "processing.h"
#include "create360.h"

void help();

struct Arg : public option::Arg
//=============================
{
   static void printError(const char *msg1, const option::Option &opt, const char *msg2)
   //-----------------------------------------------------------------------------------
   {
      fprintf(stderr, "%s", msg1);
      fwrite(opt.name, (size_t) opt.namelen, 1, stderr);
      fprintf(stderr, "%s", msg2);
      help();
   }

   static option::ArgStatus Required(const option::Option &option, bool msg)
   //-----------------------------------------------------------------------
   {
      if (option.arg != 0)
         return option::ARG_OK;

      if (msg) printError("Option '", option, "' requires an argument\n");
      return option::ARG_ILLEGAL;
   }

   static option::ArgStatus Unknown(const option::Option &option, bool msg)
   //----------------------------------------------------------------------
   {
      if (msg) printError("Unknown option '", option, "'\n");
      return option::ARG_ILLEGAL;
   }
};

const char *USAGE =
      "USAGE: postprocess [options] recording-name-1 recording-name-2 ...recording-name-n\n"
            "Example postprocess free-recording 360-recording\n";
const char *ADB_PATH_DESC =
      "-A <arg>, \t--adb=<arg> \tSpecify full location of adb including executable eg --adb=/path/to/adb\n"
            "If not specified then PATH as well as well known locations such as /opt/android-sdk/platform-tools/ will be searched";
const char *ANDROID_RECORDING_DIR_DESC =
      "-r <arg>, \t--record-dir=<arg> \tSpecify Android directory where recordings are stored.\n"
            "Defaults to /sdcard/Documents/ARRecorder/";
const char *LOCAL_RECORDING_DIR_DESC =
      "-d <arg>, \t--dir=<arg> \tSpecify desktop directory where recordings are stored.\n"
            "Defaults to ~/Documents/ARRecorder/ on Linux or equivalent on Windows";
const char *SKIP_EXISTING_DESC = "-S, \t--skip \tDon't overwrite existing files\n";
const char *ADB_DEVICE_DESC = "-s <arg>, \t--device=<arg> \tSpecify the Android device if more than one device is connected";
const char *NO_STITCH_DESC = "-t     \t--no-stitch   \tDo not perform stitching for rotational recordings";
const char *PROCESS_ONLY_DESC = "-P     \t--process \tNo adb retrieval step (assumes recording directory have already been transferred\n"
                                " to the local directory specified using -d or --dir eg for the default ~/Documents/ARRecorder/{recording})\n";

enum optionIndex { UNKNOWN, HELP, VERBOSE, ADB_PATH, ANDROID_RECORDING_DIR, LOCAL_RECORDING_DIR, ADB_DEVICE,
                   NO_STITCH, SKIP_EXISTING, PROCESS_ONLY };

const option::Descriptor usage[] =
{
   { UNKNOWN,                    0, "", "",                 Arg::Unknown, USAGE },
   { HELP,                       0, "h", "help",            Arg::None,    "-h or --help  \tPrint usage and exit." },
   { VERBOSE     ,               0, "v", "verbose",         Arg::None, "-v or --verbose"},
   { ADB_PATH,                   0, "A","adb",              Arg::Required, ADB_PATH_DESC },
   { ANDROID_RECORDING_DIR,      0, "r", "record-dir",      Arg::Required, ANDROID_RECORDING_DIR_DESC },
   { LOCAL_RECORDING_DIR,        0, "d", "dir",             Arg::Required, LOCAL_RECORDING_DIR_DESC },
   { ADB_DEVICE,                 0, "s", "device",          Arg::Required, ADB_DEVICE_DESC },
   { NO_STITCH,                  0, "t", "no-stitch",        Arg::None, NO_STITCH_DESC},
   { SKIP_EXISTING,              0, "S", "skip",            Arg::None, SKIP_EXISTING_DESC},
   { PROCESS_ONLY     ,          0, "P", "process",         Arg::None, PROCESS_ONLY_DESC },
   { 0, 0, 0, 0, 0, 0 },
};

#if defined(_WIN32) || defined(_WIN64)
char path_delimiter = ';';
char file_delimiter = '\\';
#else
char path_delimiter = ':';
char file_delimiter = '/';
#endif

bool is_verbose = false;

inline void help() { option::printUsage(std::cout, usage); }

bool make_path(const filesystem::path& path, std::string& error)
//--------------------------------------------------------------
{
   if (path.exists())
   {
      if (! path.is_directory())
      {
         error = path.str() + " exists but is not a directory";
         return false;
      }
      return true;
   }
   std::vector<std::string> components;
   filesystem::path p(path);
   while (! p.empty())
   {
      components.push_back(p.filename());
      p = p.parent_path();
   }
   std::string sofar;
   if (path.is_absolute())
      sofar += "/";
   if (! components.empty())
   {
      struct stat st;
      for (int i = static_cast<int>(components.size()) - 1; i >= 0; i--)
      {
         sofar += components[i];
         const char *path = sofar.c_str();
         if (stat(path, &st) != 0)
         {
            if ( (mkdir(path, 0774) != 0) && (errno != EEXIST) )
            {
               int err = errno;
               std::stringstream ss;
               ss << "Error creating directory for " << path << ": " << strerror(err);
               error = ss.str();
               return false;
            }
            else
               sofar += file_delimiter;
         }
         else if (! S_ISDIR(st.st_mode))
         {
            int err = ENOTDIR;
            errno = ENOTDIR;
            std::stringstream ss;
            ss << "Error creating directory for " << path << ": " << strerror(err);
            error = ss.str();
            return false;
         }
         else
            sofar += file_delimiter;
      }
   }
   return true;
}

bool adb_check(const char *envname, std::string subdir, filesystem::path& adb_path, std::string& adb)
//------------------------------------------------------------------------------------------
{
   const char* envvalue = getenv(envname);
   if (envvalue == nullptr)
   {
      std::cerr << "Environment variable " << envname << " not defined: " << std::endl;
      return false;
   }
   std::string s(envvalue);
   if (! subdir.empty())
      s = s + "/" + subdir + "/";
   else
      s = s + "/";
   adb_path = filesystem::path(s + adb);
   if (! adb_path.exists())
   {
      std::cerr << "adb not found in " << envname <<  " (" << adb_path.str() << std::endl;
      adb_path = filesystem::path("");
      return false;
   }
   else
   {
      adb = adb_path.str();
      std::cout << "adb found in " << envname << " (" << adb << ")" << std::endl;
      return true;
   }
}

bool find_in_path(std::string &adb, filesystem::path &adb_path)
//-------------------------------------------------------------
{
   const char* envvalue = ::getenv("PATH");
   if (envvalue == nullptr)
   {
      std::cerr << "PATH environment variable not defined" << std::endl;
      return false;
   }
   std::string path(envvalue);
   size_t pos = path.find(path_delimiter), last_pos = 0;
   do
   {
      adb_path = filesystem::path(path.substr(last_pos, pos - last_pos) + "/" + adb);
//      std::cout << adb_path.str() << " " << path  << " " << path_delimiter << std::endl;
      if (adb_path.exists())
      {
         adb = adb_path.make_absolute().str();
         return true;
      }
      last_pos = pos + 1;
      pos = path.find(path_delimiter, last_pos);
   } while (pos != std::string::npos);
   return false;
}

#define USE_SYSTEM
#if defined(_WIN32) || defined(_WIN64) || defined(USE_SYSTEM)
bool push_pull(std::string adb, std::string adb_device, std::string recording_file, std::string local_file,
               std::string &output, bool is_pull = true)
//-----------------------------------------------------------------------------------------------------
{
   output = "";
   std::stringstream ss;
   ss << adb << " " << ((adb_device.empty()) ? "" : "-s " + adb_device);
   if (is_pull)
      ss << " pull " << recording_file.c_str() << " " << local_file.c_str();
   else
      ss << " push " << local_file.c_str() << " " << recording_file.c_str();
   std::cout << ss.str() << std::endl;
   int status = system(ss.str().c_str());
   if (! WIFEXITED(status))
   {
      std::cerr << "Error in adb push/pull.  Exit status " << WTERMSIG(status) << std::endl;
      return false;
   }
   return (status == 0);
}
#else
bool push_pull(std::string adb, std::string adb_device, std::string recording_file, std::string local_file,
               std::string &output, bool is_pull = true)
//-----------------------------------------------------------------------------------------------------
{
   output = "";
   std::stringstream ss;
   ss << adb << " " << ((adb_device.empty()) ? "" : "-s " + adb_device);
   int argc = 4;
   if (! adb_device.empty())
      argc += 2;
   char *argv[argc];
   int i = 0;
   argv[i++] = (char *) adb.c_str();
   if (! adb_device.empty())
   {
      argv[i++] = (char *) "-s";
      argv[i++] = (char *) adb_device.c_str();
   }
   if (is_pull)
   {
      argv[i++] = (char *) "pull";
      argv[i++] = (char *) recording_file.c_str();
      argv[i++] = (char *) local_file.c_str();
      ss << " pull " << recording_file.c_str() << " " << local_file.c_str();
   }
   else
   {
      argv[i++] = (char *) "push";
      argv[i++] = (char *) local_file.c_str();
      argv[i++] = (char *) recording_file.c_str();
      ss << " push " << local_file.c_str() << " " << recording_file.c_str();
   }
   argv[i++] = (char *) NULL;
//   char *envp[2];
//   envp[0] = (char *) "ADB_TRACE=adb";
//   envp[1] = (char *) NULL;
   int link[2];
   pid_t pid;
   char buffer[32768];
   memset(buffer, 0, sizeof(buffer));
   int status = 0, err = 0;

   if (pipe(link)==-1)
   {
      int err = errno;
      std::cerr << "Error creating pipe to adb process: " << err << " " << strerror(err) << std::endl;
      return false;
   }

   if ((pid = fork()) == -1)
   {
      int err = errno;
      std::cerr << "Error forking adb process: " << err << " " << strerror(err) << std::endl;
      return false;
   }

   if (pid == 0)
   {
      dup2 (link[1], STDOUT_FILENO);
      close(link[0]);
      close(link[1]);
//      execve(adb.c_str(), argv, envp);
      execv(adb.c_str(), argv);
      int err = errno;
      std::cerr << "Error executing adb process: " << ss.str() << ": " << err << " " << strerror(err) << std::endl;
      return false;
   }
   else
   {
      close(link[1]);
      std::cout << "Waiting for " << ss.str() << " pid " << pid << std::endl;
      std::cout << std::flush;
      if (waitpid(pid, &status, 0) != pid)
         err = errno;
      if ( (err != 0) || (! WIFEXITED(status)) )
      {
         std::cerr << "Error in adb process " << ((err != 0) ? strerror(err) : "") << " Exit status " << WTERMSIG(status)
                   << std::endl;
         return false;
      }
      else
      {
         auto nbytes = read(link[0], buffer, sizeof(buffer));
         if (nbytes > 0)
            output = std::string(buffer);
      }
      close(link[0]);
   }
   return (status == 0);
}
#endif

bool send_result(std::string adb, std::string adb_device, std::string local_dir,
                 std::string android_dir, std::string recordingName, std::string &output)
//----------------------------------------------------------------------------------------------------------
{
   std::vector<std::string> files = { recordingName + ".head",  recordingName + ".frames" };
   for (std::string file : files)
   {
      std::string local_file = local_dir + file_delimiter + recordingName + file_delimiter + file;
      filesystem::path local_file_path(local_file);
      if (! local_file_path.exists())
      {
         std::cerr << local_file_path.str() << " not found." << std::endl;
         continue;
      }
      std::string recording_file = android_dir + "/" + file;
      std::string command_output;
      if (! push_pull(adb, adb_device, recording_file, local_file, command_output, false))
      {
         output = command_output;
         return false;
      }
      else if (is_verbose)
         std::cout << "Sent " << local_file << std::endl;
   }
   return true;
}

bool retrieve(std::string adb, std::string adb_device, std::string android_dir, std::string recordingName,
              std::string local_dir, bool is_skip, std::string &output)
//---------------------------------------------------------------------------------------------------------------------
{
   filesystem::path dir(local_dir);
   if (! dir.exists())
   {
      std::string error;
      if (! make_path(dir, error))
      {
         std::cerr << "Could not create directory " << dir.str() << std::endl;
         return false;
      }
   }
   std::string command_output;
   if (! push_pull(adb, adb_device, android_dir, local_dir, command_output))
   {
      output = command_output;
      return false;
   }
   else if (is_verbose)
      std::cout << "Retrieved " << local_dir << std::endl << command_output << std::endl;
   return true;
}

bool parseHeader(filesystem::path headerFile, std::unordered_map<std::string, std::string> &m)
//---------------------------------------------------------------------------------------------
{
   std::ifstream f(headerFile.str().c_str(), std::ifstream::in);
   if (!f.good())
   {
      std::cerr << "Cannot open file " << headerFile.str().c_str() << std::endl;
      return false;
   }
   char buf[120];
   std::string key, value;
   f.getline(buf, 120);
   while (!f.eof())
   {
      std::string s(buf);
      unsigned long pos = s.find_first_of("=");
      if (pos < 0)
      {
         f.getline(buf, 120);
         continue;
      }
      key = trim(s.substr(0, pos));
      try { value = trim(s.substr(pos + 1)); } catch (...) { value = ""; }
      m[key] = value;
      f.getline(buf, 120);
   }
   return true;
}


int main(int argc, char **argv)
//----------------------------
{
   argc -= (argc > 0);
   argv += (argc > 0); // skip program name argv[0] if present
   option::Stats stats(usage, argc, argv);

   std::vector<option::Option> options(stats.options_max);
   std::vector<option::Option> buffer(stats.buffer_max);
   option::Parser parse(usage, argc, argv, &options[0], &buffer[0]);

//   option::Parser parse(usage, argc, argv, options, buffer);
   if (parse.error())
      return 1;
   if ((options[HELP]) || (argc == 0) || (parse.nonOptionsCount() < 1))
   {
      help();
      return 0;
   }
   std::string local_dir, error;
#if defined(_WIN32) || defined(_WIN64)
   std::string adb = "adb.exe";
   char* home = getenv("USERPROFILE");
	if (home)
		local_dir = std::string(home);
   else
      local_dir = "C:\\Documents\\ARRecorder";
#else
   std::string adb = "adb";
   local_dir = "~/Documents/ARRecorder/";
#endif
   filesystem::path adb_path, local_path;
   std::string android_path = "/sdcard/Documents/ARRecorder/", adb_device;
   bool is_skip = false, is_retrieve = true, is_stitch = true;
   float startIncrement = 1, endIncrement = 3.0;
   int maxKludges = 20;
   for (int i = 0; i < parse.optionsCount(); ++i)
   {
      option::Option &opt = buffer[i];
      switch (opt.index())
      {
         case ADB_PATH:
            if (opt.arg)
            {
               adb = std::string(opt.arg);
               adb_path = filesystem::path(adb);
               if (! adb_path.exists())
               {
                  std::cerr << "Error: " << adb << " not found." << std::endl;
                  return 1;
               }
            }
            break;
         case ANDROID_RECORDING_DIR:
            if (opt.arg)
               android_path = std::string(opt.arg);
            break;

         case LOCAL_RECORDING_DIR:
            if (opt.arg)
               local_dir = std::string(opt.arg);
            break;

         case ADB_DEVICE:
            if (opt.arg)
               adb_device = std::string(opt.arg);
            break;

         case NO_STITCH:   is_stitch = false; break;

         case SKIP_EXISTING: is_skip = true; break;

         case PROCESS_ONLY: is_retrieve = false; break;

         case VERBOSE: is_verbose = true; break;
      }
   }
   local_path  = filesystem::path(local_dir);
   if (! local_path.exists())
   {
      if (! make_path(local_path, error))
      {
         std::cerr << "Error creating directory " << local_path << " (" << error << ")" << std::endl;
         return 1;
      }
      else if (is_verbose)
         std::cout << "Created local recording directory: " << local_path.str() << std::endl;
   }

   if (adb_path.empty())
   {
      if (!find_in_path(adb, adb_path))
      {
         if (is_retrieve)
            std::cerr << "adb not found in PATH. Checking for ANDROID_SDK_HOME and ANDROID_HOME" << std::endl;
         if (!adb_check("ANDROID_SDK_HOME", "platform-tools", adb_path, adb))
         {
            if (!adb_check("ANDROID_HOME", "platform-tools", adb_path, adb))
            {
               if (is_retrieve)
               {
                  std::cerr << "Could not find an adb executable." << std::endl;
                  return 1;
               }
            }
         }
      }
   }

   if (is_retrieve)
   {
      for (int i = 0; i < parse.nonOptionsCount(); i++)
      {
         std::string recording_name = parse.nonOption(i), output;
         std::string android_dir = android_path + "/" + recording_name,
                     dir = local_dir; // + file_delimiter + recording_name;
         if (! retrieve(adb, adb_device, android_dir, recording_name, dir, is_skip, output))
         {
            std::cerr << "Error retrieving files " << ((adb_device.empty()) ? "" : ("from " + adb_device + " "))
                      << ". Remote directory: " << android_dir << " to local directory " << dir << std::endl
                      << output << std::endl;
            return 1;
         }
      }
   }

   for (int i = 0; i < parse.nonOptionsCount(); i++)
   {
      std::string recording_name = parse.nonOption(i);
      std::string android_dir = android_path + "/" + recording_name;
      filesystem::path dir(local_dir + "/" + recording_name);
      if (! dir.exists())
      {
         std::cerr << "Local directory " << dir.make_absolute().str() << " not found. Aborting " << recording_name
                   << std::endl;
         continue;
      }
      filesystem::path headerfile(dir.str() + "/" + recording_name + ".head");
      if (! headerfile.exists())
      {
         std::cerr << "Header file " << headerfile.make_absolute().str() << " not found. Aborting " << recording_name
                   << std::endl;
         continue;
      }
      std::unordered_map<std::string, std::string> headers;
      if (! parseHeader(headerfile, headers))
      {
         std::cerr << "Could not parse header file " << headerfile.make_absolute().str() << ". Aborting " << recording_name
                   << std::endl;
         continue;
      }
      std::string recording_type = headers["Type"];
      if (recording_type.empty())
      {
         std::cerr << "Header file " << headerfile.make_absolute().str() << " does not have a Type key. Aborting "
                   << recording_name << std::endl;
         continue;
      }
      int width = atoi(trim(headerValue(headers, "PreviewWidth", "-1")).c_str());
      int height = atoi(trim(headerValue(headers, "PreviewHeight", "-1")).c_str());
      if ( (width <= 0) || (height <= 0) )
      {
         std::cerr << "Header file " << headerfile.make_absolute().str() <<
                      " has non-existent or invalid PreviewWidth/PreviewHeight. Aborting " << recording_name << std::endl;
         continue;
      }
      int yuvSize = (width * height * 12) / 8; //NV21 or YUV_420_888
      int rgbaSize = width*height*4;

      std::string orientation_filename;
      int orientationCount = 0;
      if (process_orientation(dir, headers, recording_type, orientation_filename, orientationCount))
      {
         if (headers.find("FilteredOrientationCount") == headers.end())
         {
            std::ofstream hdrout(headerfile.str(), std::ofstream::app);
            hdrout << "FilteredOrientationCount=" << orientationCount << std::endl;
            headers["FilteredOrientationCount"] = orientationCount;
         }
      }

      int shift_totalx =std::numeric_limits<int>::min(), shift_totaly =std::numeric_limits<int>::min(), framecount = -1;
      filesystem::path frames_file(dir.str() + "/frames.RGBA");
      if ( (is_skip) && (frames_file.exists()) )
      {
         shift_totalx = atoi(trim(headerValue(headers, "ShiftX", std::to_string(std::numeric_limits<int>::min()))).c_str());
         shift_totaly = atoi(trim(headerValue(headers, "ShiftY", std::to_string(std::numeric_limits<int>::min()))).c_str());
         framecount = atoi(trim(headerValue(headers, "FrameCount", "-1")).c_str());
      }
      bool isConverted = true;
      if ( (shift_totalx == std::numeric_limits<int>::min()) || (shift_totaly == std::numeric_limits<int>::min()) )
      {
         isConverted = convert_frames(dir, headers, recording_type, width, height, yuvSize, rgbaSize,
                                      shift_totalx, shift_totaly, framecount);
         if (isConverted)
         {
            std::ofstream hdrout(headerfile.str(), std::ofstream::app);
            if (headers.find("ShiftX") == headers.end())
            {
               hdrout << "ShiftX=" << shift_totalx << std::endl;
               headers["ShiftX"] = std::to_string(shift_totalx);
            }
            if (headers.find("ShiftY") == headers.end())
            {
               hdrout << "ShiftY=" << shift_totaly << std::endl;
               headers["ShiftY"] = std::to_string(shift_totaly);
            }
            if (headers.find("FrameCount") == headers.end())
            {
               hdrout << "FrameCount=" << framecount << std::endl;
               headers["FrameCount"] = std::to_string(framecount);
            }
         }
      }
      if ( (! isConverted) || (! frames_file.exists()) || (frames_file.file_size() == 0) )
      {
         std::cerr << "Frame conversion to RGBA failed. Aborting " << recording_name << std::endl;
         continue;
      }
      filesystem::path frameFile(dir.str() + "/" + string_format("%s.frames", dir.filename().c_str()));
      if (recording_type == "THREE60")
      {
         filesystem::path orientation_file(orientation_filename);
         if (! create360(dir, headerfile, frames_file, orientation_file, orientationCount, startIncrement, endIncrement, shift_totalx,
                         shift_totaly, is_stitch, maxKludges, headers))
            return 1;
      }
      else
      {
         if (frameFile.exists())
            remove(frameFile.str().c_str());
         if (rename(frames_file.str().c_str(), frameFile.str().c_str()) != 0)
            frameFile = frames_file;
         if (frameFile.exists())
         {
            std::ofstream hdrout(headerfile.str(), std::ofstream::app);
            hdrout << "FramesFile=" << frameFile.str().c_str() << std::endl;
         }
      }
      bool isSent = false;
      if ( (frameFile.exists()) && (! adb_path.empty()) )
      {
         std::string output;
         isSent = send_result(adb, adb_device, local_dir, android_dir, recording_name, output);
         if (! isSent)
            std::cerr << "Error sending updated files to Android device using adb: " << std::endl << output << std::endl;
      }
      if (! isSent)
         std::cerr << "adb not found or transfer error. Manually copy " << frameFile.str() <<  " and " << headerfile.str()
                   << " to device." << std::endl;
   }
}