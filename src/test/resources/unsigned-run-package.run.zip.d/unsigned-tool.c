#include <signal.h>

int main(void) {
    raise(SIGKILL);
    return 0;
}
